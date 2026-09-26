package dtm.di.aop.mainthread;

import dtm.di.annotations.Configuration;
import dtm.di.annotations.DisableInjectionWarn;
import dtm.di.annotations.RunOnMainThread;
import dtm.di.annotations.aop.Aspect;
import dtm.di.annotations.aop.OnMainMethod;
import dtm.di.annotations.aop.Pointcut;
import dtm.di.annotations.aop.ReferenceInstance;
import dtm.di.application.worker.MainThreadWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Aspect
@DisableInjectionWarn
public class RunOnMainThreadAspect {
    private static final Logger logger = LoggerFactory.getLogger(RunOnMainThreadAspect.class);

    private final MainThreadWorker mainThreadWorker;
    private final Map<Method, Boolean> annotatedMethodCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> configurationClassCache = new ConcurrentHashMap<>();
    private final AtomicBoolean missingWorkerWarned = new AtomicBoolean(false);

    public RunOnMainThreadAspect(@DisableInjectionWarn MainThreadWorker mainThreadWorker) {
        this.mainThreadWorker = mainThreadWorker;
    }

    @Pointcut
    public boolean pointcut(Method method, @ReferenceInstance Object instance) {
        boolean annotated = annotatedMethodCache.computeIfAbsent(
                method,
                key -> key.isAnnotationPresent(RunOnMainThread.class)
        );

        if (!annotated) {
            return false;
        }

        boolean configurationClass = configurationClassCache.computeIfAbsent(
                instance.getClass(),
                key -> key.isAnnotationPresent(Configuration.class)
        );

        if (configurationClass) {
            return false;
        }

        if (mainThreadWorker == null) {
            if (missingWorkerWarned.compareAndSet(false, true)) {
                logger.warn("@RunOnMainThread ignorado em {}: MainThreadWorker indisponível. Declare @EnableMainThreadWorker na classe bootable.", method.toGenericString());
            }
            return false;
        }

        return true;
    }

    @OnMainMethod
    public Object onMainMethod(Callable<?> callable, Method method) throws Throwable {
        Class<?> returnType = method.getReturnType();

        if (returnType == void.class || returnType == Void.class) {
            mainThreadWorker.runOnMainThread(() -> callUnchecked(callable));
            return null;
        }

        if (returnType == CompletableFuture.class || returnType == CompletionStage.class) {
            return flatten(mainThreadWorker.callOnMainThread(callable));
        }

        if (returnType == Future.class) {
            return mainThreadWorker.callOnMainThread(() -> unwrapFuture(callable.call()));
        }

        if (mainThreadWorker.isMainThread()) {
            return callable.call();
        }

        try {
            return mainThreadWorker.callOnMainThread(callable).join();
        } catch (CompletionException e) {
            throw unwrapException(e);
        }
    }

    private static CompletableFuture<Object> flatten(CompletableFuture<?> source) {
        CompletableFuture<Object> result = new CompletableFuture<>();

        source.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrapException(error));
            } else if (value instanceof CompletionStage<?> stage) {
                stage.whenComplete((inner, innerError) -> {
                    if (innerError != null) {
                        result.completeExceptionally(unwrapException(innerError));
                    } else {
                        result.complete(inner);
                    }
                });
            } else {
                result.complete(value);
            }
        });

        return result;
    }

    private static Object unwrapFuture(Object result) throws Exception {
        if (result instanceof Future<?> future) {
            return future.get();
        }

        return result;
    }

    private static Throwable unwrapException(Throwable throwable) {
        if ((throwable instanceof CompletionException
                || throwable instanceof ExecutionException)
                && throwable.getCause() != null) {
            return throwable.getCause();
        }

        return throwable;
    }

    private static void callUnchecked(Callable<?> callable) {
        try {
            callable.call();
        } catch (Throwable throwable) {
            RunOnMainThreadAspect.<RuntimeException>throwUnchecked(throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
