package dtm.di.startup;

import dtm.di.annotations.schedule.EnableSchedule;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.annotations.boot.LifecycleHook;
import dtm.di.annotations.boot.OnBoot;
import dtm.di.annotations.scanner.PackageScanIgnore;
import dtm.di.annotations.schedule.Schedule;
import dtm.di.annotations.schedule.ScheduleMethod;
import dtm.di.core.DependencyContainer;
import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.exceptions.boot.InvalidBootException;
import dtm.di.storage.DependencyContainerStorage;
import dtm.discovery.core.ClassFinderConfigurations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ManagedApplicationStartup {
    private static final Logger logger = LoggerFactory.getLogger(ManagedApplicationStartup.class);
    private static Method runMethod;
    private static DependencyContainer dependencyContainer;
    private static Class<?> bootableClass;
    private static Class<?> mainClass;
    private static Map<LifecycleHook.Event, List<Method>> eventMethodMap;
    private static boolean logEnabled;
    private static boolean aopEnable = true;
    private static ScheduledExecutorService scheduledExecutorService;

    public static void doRun(){
        doRun(false);
    }

    public static void doRun(boolean log){
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

        dependencyContainer = getDependencyContainer();
        logInfo("DependencyContainer obtido");

        runMethod = getRunMethod();
        logInfo("Método @OnBoot encontrado: {}", runMethod.getName());

        aopEnable = aopIsEnable();
        logInfo("AOP: {}", ((aopEnable) ? "Habilitado" : "Desativado com @DisableAop"));

        runAsync();
        logInfo("doRun() finalizado");
    }

    public static DependencyContainer getCurrentDependencyContainer(){
        return dependencyContainer;
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

    private static Method getRunMethod(){
        for (Method method : bootableClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnBoot.class)) {
                int mods = method.getModifiers();
                boolean isValid = Modifier.isStatic(mods) &&
                        Modifier.isPublic(mods) &&
                        method.getReturnType() == void.class;

                if(isValid) return method;
            }
        }

        throw new InvalidBootException("Nenhum método @OnBoot válido encontrado na classe " + bootableClass.getName());
    }

    private static DependencyContainer getDependencyContainer(){
        DependencyContainerStorage dependencyContainerStorage = DependencyContainerStorage.getInstance(mainClass);
        applyPeckageScan(dependencyContainerStorage.getClassFinderConfigurations());

        return dependencyContainerStorage;
    }

    private static Map<LifecycleHook.Event, List<Method>> getEventMethodMap() {
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
        AtomicReference<Throwable> exception = new AtomicReference<>(null);
        logLifecycle("BOOT_START", true);
        invokeHooks(LifecycleHook.Event.BEFORE_ALL);
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                logLifecycle("CONTAINER_LOAD", true);
                dependencyContainer.enableParallelInjection();
                if(aopEnable) dependencyContainer.enableAOP();
                dependencyContainer.load();
                logLifecycle("CONTAINER_LOAD", false);
            } catch (InvalidClassRegistrationException e) {
                logError("Erro ao carregar DependencyContainer: {}", e.getMessage(), e);
                exception.set(e);
            }
        }).whenComplete((res, ex) -> {
            if (ex != null) {
                Throwable rootCause = getRootCause(ex);
                exception.set(rootCause);
                logError("Erro durante carregamento assíncrono: {}", rootCause.getMessage(), rootCause);
            }
            invokeHooks(LifecycleHook.Event.AFTER_CONTAINER_LOAD);
            try {
                logLifecycle("STARTUP_METHOD", true);
                runSchedulerAsync();
                runMethod.invoke(null);
                logLifecycle("STARTUP_METHOD", false);
            } catch (Exception e) {
                Throwable rootCause = getRootCause(e);
                exception.set(rootCause);
                logError("Erro ao executar método @OnBoot: {}", rootCause.getMessage(), rootCause);
            }
            invokeHooks(LifecycleHook.Event.AFTER_STARTUP_METHOD);
        });

        future.join();
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
                for(Class<?> clazz : dependencyContainer.getLoadedSystemClasses()){
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

        Object instance = dependencyContainer.newInstance(clazz);

        for(Method method : methods){
            ScheduleMethod scheduleMethod = method.getAnnotation(ScheduleMethod.class);
            long time = (scheduleMethod.time() > 0) ? scheduleMethod.time() : 1000;
            long delay = (scheduleMethod.startDelay() > 0) ? scheduleMethod.startDelay() : 0;
            TimeUnit timeUnit =(scheduleMethod.timeUnit() != null) ? scheduleMethod.timeUnit() : TimeUnit.MILLISECONDS;

            scheduledExecutorService.scheduleAtFixedRate(() -> {
                try{
                    method.setAccessible(true);
                    method.invoke(instance);
                }catch (Exception e){
                    Throwable rootCause = getRootCause(e);
                    logError("Erro ao executar schedule {} no método {}: {}", method.getName(), rootCause.getMessage(), rootCause);
                }

            }, delay, time, timeUnit);
        }
    }

}
