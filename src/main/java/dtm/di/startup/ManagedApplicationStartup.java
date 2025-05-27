package dtm.di.startup;

import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.annotations.boot.LifecycleHook;
import dtm.di.annotations.boot.OnBoot;
import dtm.di.core.DependencyContainer;
import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.exceptions.boot.InvalidBootException;
import dtm.di.storage.DependencyContainerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class ManagedApplicationStartup {
    private static final Logger logger = LoggerFactory.getLogger(ManagedApplicationStartup.class);
    private static Method runMethod;
    private static DependencyContainer dependencyContainer;
    private static Class<?> bootableClass;
    private static Class<?> mainClass;
    private static Map<LifecycleHook.Event, List<Method>> eventMethodMap;
    private static boolean logEnabled;


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

        eventMethodMap = getEventMethodMap();
        logInfo("Hooks carregados para os eventos: {}", eventMethodMap.keySet());

        dependencyContainer = getDependencyContainer();
        logInfo("DependencyContainer obtido");

        runMethod = getRunMethod();
        logInfo("Método @OnBoot encontrado: {}", runMethod.getName());

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
        return DependencyContainerStorage.getInstance(mainClass);
    }

    private static Map<LifecycleHook.Event, List<Method>> getEventMethodMap() {
        Map<LifecycleHook.Event, List<Method>> hookMap = new EnumMap<>(LifecycleHook.Event.class);

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

            List<Method> methods = hookMap.computeIfAbsent(event, k -> new java.util.ArrayList<>());

            methods.add(method);
        }

        return hookMap;
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
                    logError("Erro ao executar hook {} no método {}: {}", event, method.getName(), e.getMessage(), e);
                    throw new InvalidBootException("Erro ao executar hook " + event + " no método "
                            + method.getName() + ": " + e.getMessage(), e);
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
                dependencyContainer.enableAOP();
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



}
