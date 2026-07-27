package dtm.di.prototypes.async;

import dtm.di.storage.async.AsyncResultWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public interface AsyncResult<T> {

    /**
     * Cria um resultado assíncrono já concluído com o objeto informado.
     *
     * @param object objeto que será disponibilizado pelo resultado
     * @param <T> tipo do objeto
     * @return resultado concluído com {@code object}
     */
    static <T> AsyncResult<T> ofObject(T object) {
        return new AsyncResultWrapper<>(CompletableFuture.completedFuture(object));
    }

    /**
     * Sinônimo de {@link #ofObject(Object)}.
     */
    static <T> AsyncResult<T> ofResult(T object) {
        return ofObject(object);
    }

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
