package dtm.di.prototypes.async;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public interface AsyncResult<T> {
    AsyncResult<T> onSuccess(Consumer<T> action);
    AsyncResult<T> onError(Consumer<Throwable> errorHandler);
    <R> AsyncResult<R> thenApply(Function<T, R> mapper);
    AsyncResult<T> whenComplete(BiConsumer<? super T, ? super Throwable> action);

    default AsyncResult<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteAsync(action, ForkJoinPool.commonPool());
    }

    AsyncResult<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor);
    T await();
}
