package dtm.di.aop.async;

import dtm.di.annotations.Async;
import dtm.di.annotations.DisableInjectionWarn;
import dtm.di.annotations.aop.*;
import dtm.di.exceptions.AsyncMethodException;
import dtm.di.settings.async.AsyncExecutorFactory;
import java.lang.reflect.Method;
import java.util.concurrent.*;

@Aspect
@DisableInjectionWarn
public class AsyncAspect {

    private final AsyncExecutorFactory asyncExecutorFactory;

    public AsyncAspect(@DisableInjectionWarn AsyncExecutorFactory asyncExecutorFactory) {
        this.asyncExecutorFactory = (asyncExecutorFactory != null) ? asyncExecutorFactory : AsyncExecutorFactory.ofSingleton(ForkJoinPool.commonPool());
    }

    @Pointcut
    public boolean pointcut(Method method, @ReferenceInstance Object instance) {
        return method.isAnnotationPresent(Async.class);
    }

    @OnMainMethod
    public Object onMainMethod(Callable<?> callable, Method method, Object[] args, @ReferenceInstance Object instance) throws Throwable {
        ExecutorService executor = getExecutor();
        Class<?> returnType = method.getReturnType();

        if (returnType == void.class || returnType == Void.class) {
            executor.execute(() -> executeUnchecked(callable));
            return null;
        }

        if (returnType == CompletableFuture.class || returnType == CompletionStage.class) {
            return submitCompletable(callable, executor);
        }

        if (returnType == Future.class) {
            return executor.submit(() -> unwrapFuture(callable.call()));
        }

        throw new AsyncMethodException(
                "Métodos anotados com @Async devem retornar "
                        + "void, Future ou CompletableFuture: "
                        + method.toGenericString(),
                method,
                instance.getClass()
        );
    }

    private ExecutorService getExecutor() {
        ExecutorService executor = asyncExecutorFactory.getExecutor();
        return executor != null ? executor : ForkJoinPool.commonPool();
    }

    private static CompletableFuture<Object> submitCompletable(Callable<?> callable, Executor executor) {
        CompletableFuture<Object> result = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                Object invocationResult = callable.call();

                if (invocationResult instanceof CompletionStage<?> stage) {
                    stage.whenComplete((value, error) -> {
                        if (error != null) {
                            result.completeExceptionally(unwrapException(error));
                        } else {
                            result.complete(value);
                        }
                    });
                } else {
                    result.complete(invocationResult);
                }
            } catch (Throwable throwable) {
                result.completeExceptionally(unwrapException(throwable));
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

    private static void executeUnchecked(Callable<?> callable) {
        try {
            callable.call();
        } catch (Throwable throwable) {
            AsyncAspect.<RuntimeException>throwUnchecked(throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
