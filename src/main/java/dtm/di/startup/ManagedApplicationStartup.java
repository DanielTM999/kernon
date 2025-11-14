package dtm.di.startup;

import dtm.di.annotations.ControllerAdvice;
import dtm.di.annotations.DependencyContainerFactory;
import dtm.di.annotations.boot.OnApplicationFail;
import dtm.di.annotations.handler.ExceptionHandler;
import dtm.di.annotations.schedule.EnableSchedule;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.annotations.boot.LifecycleHook;
import dtm.di.annotations.boot.OnBoot;
import dtm.di.annotations.scanner.PackageScanIgnore;
import dtm.di.annotations.schedule.Schedule;
import dtm.di.annotations.schedule.ScheduleMethod;
import dtm.di.common.DefaultStopWatch;
import dtm.di.common.StopWatch;
import dtm.di.core.ClassFinderDependencyContainer;
import dtm.di.core.DependencyContainer;
import dtm.di.core.ExceptionHandlerInvoker;
import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.exceptions.NewInstanceException;
import dtm.di.exceptions.boot.InvalidBootException;
import dtm.di.prototypes.Dependency;
import dtm.di.storage.StaticContainer;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.storage.handler.ControllerAdviceHandlerInvokeService;
import dtm.di.storage.handler.ExceptionHandlerInvokerService;
import dtm.discovery.core.ClassFinderConfigurations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.InvalidParameterException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ManagedApplicationStartup {
    private static final Logger logger = LoggerFactory.getLogger(ManagedApplicationStartup.class);
    private static Method runMethod;
    private static Method throwableMethod;
    private static Class<?> bootableClass;
    private static Class<?> mainClass;
    private static Map<LifecycleHook.Event, List<Method>> eventMethodMap;
    private static boolean logEnabled;
    private static boolean aopEnable = true;
    private static ScheduledExecutorService scheduledExecutorService;
    private static Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private static ExceptionHandlerInvoker handlerInvoker;
    private static Future<Void> controllerAdviceScanner;
    private final static AtomicReference<DependencyContainer> dependencyContainerRef = new AtomicReference<>();
    private final static AtomicReference<String[]> lauchArgsRef = new AtomicReference<>(new String[0]);
    private final static AtomicReference<ExceptionHandlerInvoker> userControllerAdvice = new AtomicReference<>();
    private final static AtomicBoolean controllerAdviceScannerIsLoad = new AtomicBoolean(false);


    public static void doRun(){
        doRun(false, new String[0]);
    }

    public static void doRun(boolean log){
        doRun(log, new String[0]);
    }

    public static void doRun(String[] args){
        doRun(false, args);
    }

    public static void doRun(boolean log, String[] args){
        lauchArgsRef.set(args);
        handlerInvoker = getDefaultExceptionHandlerInvoker();
        setExceptionHandler();
        logEnabled = log;
        logInfo("Iniciando doRun()");
        mainClass = getMainClass();
        if(mainClass == null) {
            logError("Classe main não encontrada");
            throw new InvalidBootException("Classe main não encontrado");
        }
        logInfo("Classe main encontrada: {}", mainClass.getName());

        bootableClass = getBootableClass();
        logInfo("Classe bootable definida: {}", bootableClass.getName());

        configuraSchedule();
        logInfo("Schedule "+((scheduledExecutorService == null) ? "Dasativado" : "Ativado"));

        eventMethodMap = getEventMethodMap();
        logInfo("Hooks carregados para os eventos: {}", eventMethodMap.keySet());

        dependencyContainerRef.set(getDependencyContainer());
        logInfo("DependencyContainer obtido");

        getRunMethod();
        logInfo("Método @OnBoot encontrado: {}", runMethod.getName());
        if (throwableMethod != null) {
            logInfo("Handler para erros identificado no método @OnApplicationFail: {}.", throwableMethod.getName());
            defineExceptionHandler(false);
        } else {
            logInfo("Nenhum handler para erros (@OnApplicationFail) foi definido; exceções poderão ser propagadas normalmente.");
        }

        aopEnable = aopIsEnable();
        logInfo("AOP: {}", ((aopEnable) ? "Habilitado" : "Desativado com @DisableAop"));

        runAsync();
        logInfo("doRun() finalizado");
    }

    public static DependencyContainer getCurrentDependencyContainer(){
        return dependencyContainerRef.get();
    }

    private static Class<?> getMainClass(){
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = stack.length - 1; i >= 0; i--) {
            try {
                Class<?> clazz = Class.forName(stack[i].getClassName());
                Method mainMethod = clazz.getMethod("main", String[].class);
                int mods = mainMethod.getModifiers();

                if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && mainMethod.getReturnType() == void.class) {
                    return clazz;
                }
            } catch (Exception ignored) {}

        }
        return null;

    }

    private static Class<?> getBootableClass(){
        Class<?> bootableClass = mainClass;
        if(mainClass.isAnnotationPresent(ApplicationBoot.class)){
            ApplicationBoot applicationBoot = mainClass.getAnnotation(ApplicationBoot.class);
            bootableClass = (applicationBoot.value() != null) ? applicationBoot.value() : mainClass;
        }
        return bootableClass;
    }

    private static void getRunMethod(){
        for (Method method : bootableClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnBoot.class)) {
                int mods = method.getModifiers();
                boolean isValid = Modifier.isStatic(mods) &&
                        Modifier.isPublic(mods) &&
                        method.getReturnType() == void.class;

                if(isValid) runMethod = method;
            }else if(method.isAnnotationPresent(OnApplicationFail.class)){
                int mods = method.getModifiers();
                boolean isValid = Modifier.isStatic(mods) &&
                        Modifier.isPublic(mods) &&
                        method.getReturnType() == void.class;

                Class<?>[] params = method.getParameterTypes();
                boolean hasValidParams = (params.length == 1 && Throwable.class.isAssignableFrom(params[0]))
                        || (params.length == 2
                        && Throwable.class.isAssignableFrom(params[0])
                        && Thread.class.isAssignableFrom(params[1]));

                if (isValid && hasValidParams) throwableMethod = method;
            }
        }

        if(runMethod == null){
            throw new InvalidBootException("Nenhum método @OnBoot válido encontrado na classe " + bootableClass.getName());
        }
    }

    private static DependencyContainer getDependencyContainer(){

        DependencyContainerFactory containerFactory = bootableClass.getAnnotation(DependencyContainerFactory.class);

        if(containerFactory != null){
            Class<? extends DependencyContainer> clazz = containerFactory.value();
            tryLoad(clazz);
            DependencyContainer dependencyContainer = StaticContainer.getDependencyContainer(clazz);

            if(dependencyContainer == null){
                DependencyContainerStorage dependencyContainerStorage = DependencyContainerStorage.getInstance(mainClass);
                applyPeckageScan(dependencyContainerStorage.getClassFinderConfigurations());
                return dependencyContainerStorage;
            }

            if(dependencyContainer instanceof  ClassFinderDependencyContainer dependencyContainerStorage){
                applyPeckageScan(dependencyContainerStorage.getClassFinderConfigurations());
                return dependencyContainerStorage;
            }else{
                return dependencyContainer;
            }

        }else{
            DependencyContainerStorage dependencyContainerStorage = DependencyContainerStorage.getInstance(mainClass);
            applyPeckageScan(dependencyContainerStorage.getClassFinderConfigurations());
            logInfo("Container {} obtido com sucesso", DependencyContainer.class);
            return dependencyContainerStorage;
        }

    }

    private static Map<LifecycleHook.Event, List<Method>> getEventMethodMap() {
        StopWatch sw = new DefaultStopWatch("getEventMethodMap");
        Map<LifecycleHook.Event, Map<Method, Integer>> hookMap = new EnumMap<>(LifecycleHook.Event.class);

        for (Method method : bootableClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(LifecycleHook.class)) continue;

            int mods = method.getModifiers();
            boolean isValid = Modifier.isStatic(mods)
                    && Modifier.isPublic(mods)
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 0;

            if (!isValid) {
                logWarn("@LifecycleHook ignorado (deve ser public static void sem args): {}#{}", method.getDeclaringClass().getName(), method.getName());
                continue;
            }

            LifecycleHook hook = method.getAnnotation(LifecycleHook.class);
            LifecycleHook.Event event = hook.value();
            int order =  hook.order();

            Map<Method, Integer> methodMap = hookMap.computeIfAbsent(event, k -> new HashMap<>());

            methodMap.put(method, order);
        }

        Map<LifecycleHook.Event, List<Method>> sortedMap = new EnumMap<>(LifecycleHook.Event.class);

        for (Map.Entry<LifecycleHook.Event, Map<Method, Integer>> entry : hookMap.entrySet()) {
            List<Map.Entry<Method, Integer>> entries = new ArrayList<>(entry.getValue().entrySet());

            entries.sort(Comparator.comparingInt(Map.Entry::getValue));

            List<Method> sortedMethods = entries.stream()
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            sortedMap.put(entry.getKey(), sortedMethods);
        }


        return sortedMap;
    }

    private static void invokeHooks(LifecycleHook.Event event) {
        logInfo("Invocando hooks para o evento {}", event);
        List<Method> methods = eventMethodMap.get(event);
        if (methods != null) {
            for (Method method : methods) {
                try {
                    logDebug("Executando hook: {}#{}", method.getDeclaringClass().getSimpleName(), method.getName());
                    method.invoke(null);
                    logDebug("Hook executado com sucesso: {}#{}", method.getDeclaringClass().getSimpleName(), method.getName());
                } catch (Exception e) {
                    Throwable rootCause = getRootCause(e);
                    logError("Erro ao executar hook {} no método {}: {}", event, method.getName(), rootCause.getMessage(), rootCause);
                    throw new InvalidBootException("Erro ao executar hook " + event + " no método "
                            + method.getName() + ": " + rootCause.getMessage(), rootCause);
                }
            }
        }else{
            logDebug("Nenhum hook encontrado para o evento {}", event);
        }
    }

    private static void runAsync(){
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("BootThread");
            return thread;
        });
        AtomicReference<Throwable> exception = new AtomicReference<>(null);
        logLifecycle("BOOT_START", true);
        invokeHooks(LifecycleHook.Event.BEFORE_ALL);
        DependencyContainer dependencyContainer = getCurrentDependencyContainer();
        try(executor){
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    logLifecycle("CONTAINER_LOAD", true);
                    if(aopEnable) dependencyContainer.enableAOP();
                    dependencyContainer.load();
                    defineControllerAdviceAsync();
                    logLifecycle("CONTAINER_LOAD", false);
                } catch (InvalidClassRegistrationException e) {
                    logError("Erro ao carregar DependencyContainer: {}", e.getMessage(), e);
                    exception.set(e);
                }
            }, executor).whenComplete((res, ex) -> {
                if (ex != null) {
                    Throwable rootCause = getRootCause(ex);
                    exception.set(rootCause);
                    logError("Erro durante carregamento assíncrono: {}", rootCause.getMessage(), rootCause);
                    return;
                }
                defineExceptionHandler(true);
                invokeHooks(LifecycleHook.Event.AFTER_CONTAINER_LOAD);
                try {
                    logLifecycle("STARTUP_METHOD", true);
                    runSchedulerAsync();
                    runStarterMethod();
                    logLifecycle("STARTUP_METHOD", false);
                } catch (Exception e) {
                    Throwable rootCause = getRootCause(e);
                    exception.set(rootCause);
                    logError("Erro ao executar método @OnBoot: {}", rootCause.getMessage(), rootCause);
                }
                invokeHooks(LifecycleHook.Event.AFTER_STARTUP_METHOD);
            });

            future.join();
        }
        invokeHooks(LifecycleHook.Event.AFTER_ALL);
        logLifecycle("BOOT_COMPLETE", false);
        if (exception.get() != null) {
            Throwable t = exception.get();
            throw new InvalidBootException("Erro durante boot da aplicação", t);
        }

    }

    private static void runSchedulerAsync(){
        if(scheduledExecutorService != null){
            CompletableFuture.runAsync(() -> {
                for(Class<?> clazz : getCurrentDependencyContainer().getLoadedSystemClasses()){
                    if(!clazz.isAnnotationPresent(Schedule.class)) continue;
                    try{
                        executeScheduleItem(clazz);
                    }catch (Exception e){
                        Throwable rootCause = getRootCause(e);
                        logError("Erro ao executar schedule {} na classe {}: {}", clazz.getName(), rootCause.getMessage(), rootCause);
                    }

                }
            });
        }
    }

    private static void runStarterMethod() throws InvocationTargetException, IllegalAccessException {
        Object[] methodArgs = new Object[runMethod.getParameterCount()];

        Class<?>[] parameterTypes = runMethod.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if(parameterTypes[i].equals(DependencyContainer.class)){
                methodArgs[i] = getCurrentDependencyContainer();
            } else if (parameterTypes[i].equals(String[].class)) {
                methodArgs[i] = lauchArgsRef.get();
            }
        }
        runMethod.invoke(null, methodArgs);
    }

    private static void logLifecycle(String label, boolean start) {
        String phase = switch (label) {
            case "BOOT_START" -> "Iniciando processo de boot";
            case "CONTAINER_LOAD" -> "Carregando container de dependências";
            case "STARTUP_METHOD" -> "Executando método de inicialização";
            case "BOOT_COMPLETE" -> "Processo de boot finalizado";
            default -> "Fase desconhecida";
        };

        if (start) {
            logInfo("{}...", phase);
        } else {
            logInfo("{} concluído", phase);
        }
    }

    private static void logInfo(String msg, Object... args) {
        if (logEnabled) logger.info(msg, args);
    }

    private static void logDebug(String msg, Object... args) {
        if (logEnabled) logger.debug(msg, args);
    }

    private static void logWarn(String msg, Object... args) {
        if (logEnabled) logger.warn(msg, args);
    }

    private static void logError(String msg, Object... args) {
        if (logEnabled) logger.error(msg, args);
    }

    private static Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable;

        while (true) {
            if (cause instanceof InvocationTargetException invocationTargetException) {
                Throwable target = invocationTargetException.getTargetException();
                if (target != null && target != cause) {
                    cause = target;
                    continue;
                }
            }
            Throwable nextCause = cause.getCause();
            if (nextCause == null || nextCause == cause) {
                break;
            }
            cause = nextCause;
        }

        return cause;
    }

    private static boolean aopIsEnable(){
        return !bootableClass.isAnnotationPresent(DisableAop.class);
    }

    private static void configuraSchedule(){
        if(bootableClass.isAnnotationPresent(EnableSchedule.class)){
            EnableSchedule enableSchedule = bootableClass.getAnnotation(EnableSchedule.class);
            int numThreads = (enableSchedule.threads() > 1) ? enableSchedule.threads() : 2;

            scheduledExecutorService = new ScheduledThreadPoolExecutor(numThreads);
        }
    }

    private static void applyPeckageScan(ClassFinderConfigurations classFinderConfigurations){
        PackageScanIgnore[] packageScanIgnoreList = bootableClass.getAnnotationsByType(PackageScanIgnore.class);

        for (PackageScanIgnore packageScanIgnore : packageScanIgnoreList){
            PackageScanIgnore.ScanType scanType = packageScanIgnore.scanType();
            Set<String> ignorePackage = new HashSet<>(Arrays.asList(packageScanIgnore.ignorePackage()));
            String scanElement = packageScanIgnore.scanElement();

            if(scanElement.equalsIgnoreCase("jar")){
                add(classFinderConfigurations.getIgnoreJarsTerms(), ignorePackage, scanType);
            }else{
                add(classFinderConfigurations.getIgnorePackges(), ignorePackage, scanType);
            }
        }
    }

    private static void add(List<String> base, Set<String> toAdd, PackageScanIgnore.ScanType scanType){
        if(scanType == PackageScanIgnore.ScanType.INCREMENT){
            base.addAll(toAdd);
        }else if(scanType == PackageScanIgnore.ScanType.REPLACE){
            base.clear();
            base.addAll(toAdd);
        }
    }

    private static void executeScheduleItem(Class<?> clazz) throws Exception{

        Set<Method> methods = Arrays.stream(clazz.getDeclaredMethods())
                .filter(mtd -> {
                    boolean isAnoted = mtd.isAnnotationPresent(ScheduleMethod.class);
                    boolean nonParameter = mtd.getParameterCount() == 0;
                    return isAnoted && nonParameter;
                }).collect(Collectors.toSet());

        if(methods.isEmpty()) return;

        Object instance = getCurrentDependencyContainer().newInstance(clazz);

        for(Method method : methods){
            ScheduleMethod scheduleMethod = method.getAnnotation(ScheduleMethod.class);
            long time = (scheduleMethod.time() > 0) ? scheduleMethod.time() : 1000;
            long delay = (scheduleMethod.startDelay() > 0) ? scheduleMethod.startDelay() : 0;
            TimeUnit timeUnit =(scheduleMethod.timeUnit() != null) ? scheduleMethod.timeUnit() : TimeUnit.MILLISECONDS;
            boolean periadic = scheduleMethod.periodic();

            Runnable task = () -> {
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (Exception e) {
                    Throwable rootCause = getRootCause(e);
                    logError("Erro ao executar schedule {} no método {}: {}", method.getName(), rootCause.getMessage(), rootCause);
                }
            };

            if(periadic){
                scheduledExecutorService.scheduleAtFixedRate(task, delay, time, timeUnit);
            }else{
                scheduledExecutorService.schedule(task, delay, timeUnit);
            }
        }
    }

    private static void tryLoad(Class<? extends DependencyContainer> clazz){
        try{
            Method method = clazz.getMethod("loadInstance", Class.class, String[].class);
            method.invoke(null, mainClass, new String[0]);
            logInfo("Container {} obtido com sucesso", clazz.getName());
        }catch (Exception e){
            logInfo("Nenhum container encontrado para {}, instanciando DependencyContainer padrão", clazz.getName());
        }
    }

    private static void setExceptionHandler(){
        Thread.setDefaultUncaughtExceptionHandler(ManagedApplicationStartup::exceptionHandlerAction);
    }

    private static void exceptionHandlerAction(Thread thread, Throwable throwable){
        try{
            completeLoadControllerAdvice();
            final ExceptionHandlerInvoker userExceptionHandlerInvoker = userControllerAdvice.get();
            if(userExceptionHandlerInvoker != null){
                userExceptionHandlerInvoker.invoke(thread, throwable);
            }else{
                try{
                    handlerInvoker.invoke(thread, throwable);
                }catch (Exception e){
                    logger.error("", throwable);
                }
            }
        } catch (Exception ignored) {
            try{
                handlerInvoker.invoke(thread, throwable);
            }catch (Exception e){
                logger.error("", throwable);
            }
        }
    }

    private static void defineExceptionHandler(boolean defineByDependencyContainer){
        if(defineByDependencyContainer){
            defineDependencyContainerExceptionHandler();
        }else{
            defineSimpleExceptionHandler();
        }
    }

    private static void defineSimpleExceptionHandler(){
        if(throwableMethod != null){
            logInfo("Definindo Exception Handler simples utilizando método anotado com @OnApplicationFail: {}", throwableMethod.getName());
            handlerInvoker = (thread, throwable) -> {
                try{
                    Class<?>[] paramsArgs = throwableMethod.getParameterTypes();
                    Object[] args = new Object[paramsArgs.length];

                    for (int i = 0; i < paramsArgs.length; i++) {
                        if (Throwable.class.isAssignableFrom(paramsArgs[i])) {
                            args[i] = throwable;
                        } else if (Thread.class.isAssignableFrom(paramsArgs[i])) {
                            args[i] = thread;
                        } else {
                            throw new InvalidParameterException("paramtro invalido esperava 'Throwable', 'Thread'");
                        }
                    }

                    logInfo("Um erro foi detectado durante o processo da aplicação. Encaminhando a exceção para o manipulador definido em @OnApplicationFail: {}", throwableMethod.getName());
                    throwableMethod.invoke(null, args);
                    handlerInvoker.invoke(thread, throwable);
                }catch (Exception e){
                    logError("Falha ao executar o handler @OnApplicationFail '{}'. Encaminhando para handler padrão.", throwableMethod.getName(), e);
                    uncaughtExceptionHandler.uncaughtException(thread, throwable);
                }
            };
        }else{
            logInfo("Nenhum Exception Handler definido via anotação @OnApplicationFail. Aplicando handler padrão (UncaughtExceptionHandler).");
            handlerInvoker = new ExceptionHandlerInvoker() {
                @Override
                public void invoke(Thread thread, Throwable throwable) throws Exception {
                    uncaughtExceptionHandler.uncaughtException(thread, throwable);
                }
            };
        }
    }

    private static void defineDependencyContainerExceptionHandler(){
        DependencyContainer dependencyContainer = getCurrentDependencyContainer();
        if(dependencyContainer == null){
            defineSimpleExceptionHandler();
            return;
        }

        Optional<Class<?>> dependencyContainerExceptionHandlerClassOpt =  dependencyContainer.getLoadedSystemClasses()
                .parallelStream()
                .filter(e -> e.isAnnotationPresent(ExceptionHandler.class))
                .findFirst();

        if(dependencyContainerExceptionHandlerClassOpt.isEmpty()) {
            defineSimpleExceptionHandler();
            return;
        }

        Class<?> handlerClass = dependencyContainerExceptionHandlerClassOpt.get();
        try{
            logWarn("Delegando Exception handler para [{}]", handlerClass.getName());
            handlerInvoker = new ExceptionHandlerInvokerService(handlerClass, dependencyContainer);
        }catch (NewInstanceException exception){
            logError("Falha ao instanciar o Exception Handler [{}]. Usando handler simples.", handlerClass.getName(), exception);
            defineSimpleExceptionHandler();
        }

    }

    private static ExceptionHandlerInvoker getDefaultExceptionHandlerInvoker(){
        return new ExceptionHandlerInvoker() {
            @Override
            public void invoke(Thread thread, Throwable throwable) throws Exception {

                if(uncaughtExceptionHandler != null){
                    uncaughtExceptionHandler.uncaughtException(thread, throwable);
                    return;
                }

                logger.error("Exceção não tratada na thread {}: ", thread.getName(), throwable);
            }
        };
    }


    private static void defineControllerAdviceAsync(){
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("ControllerAdviceScannerThread");
            return thread;
        });
        final DependencyContainer dependencyContainer = getCurrentDependencyContainer();
        try(executor){
            controllerAdviceScanner = CompletableFuture.runAsync(() -> {
                try {
                    logInfo("Iniciando varredura de classes para localizar @ControllerAdvice...");

                    Class<?> classOfControllerAdvice = null;
                    for (Class<?> classOfService : dependencyContainer.getLoadedSystemClasses()) {
                        if (classOfService.isAnnotationPresent(ControllerAdvice.class)) {
                            classOfControllerAdvice = classOfService;
                            break;
                        }
                    }

                    if (classOfControllerAdvice != null) {
                        logInfo("ControllerAdvice encontrado: " + classOfControllerAdvice.getName());

                        Object controllerAdviceInstance = dependencyContainer.newInstance(classOfControllerAdvice);
                        if(controllerAdviceInstance != null){
                            userControllerAdvice.set(new ControllerAdviceHandlerInvokeService(controllerAdviceInstance, handlerInvoker));
                        }
                    } else {
                        logWarn("Nenhuma classe anotada com @ControllerAdvice foi encontrada.");
                    }
                } catch (Exception e) {
                    logError("Erro inesperado durante a varredura de ControllerAdvice", e);
                }
            }, executor);
        } catch (Exception e) {
            logError("Erro ao definir Controller Advice", e);
        }
    }

    private static void completeLoadControllerAdvice(){
        try{
            if(controllerAdviceScannerIsLoad.compareAndSet(false, true)){
                controllerAdviceScanner.get();
            }
        }catch (Exception e){
            logError("Erro ao carregar ControllerAdvice", e);
        }
    }
}
