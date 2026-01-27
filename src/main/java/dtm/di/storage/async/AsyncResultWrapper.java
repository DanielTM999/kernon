package dtm.di.storage.async;

import dtm.di.prototypes.async.AsyncResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class AsyncResultWrapper<T> implements AsyncResult<T> {

    private final CompletableFuture<T> future;

    public AsyncResultWrapper(CompletableFuture<T> future) {
        this.future = future;
    }

    @Override
    public AsyncResult<T> onSuccess(Consumer<T> action) {
        future.thenAccept(action);
        return this;
    }

    @Override
    public AsyncResult<T> onError(Consumer<Throwable> errorHandler) {
        future.exceptionally(ex -> {
            errorHandler.accept(ex);
            return null;
        });
        return this;
    }

    @Override
    public <R> AsyncResult<R> thenApply(Function<T, R> mapper) {
        return new AsyncResultWrapper<>(future.thenApply(mapper));
    }

    @Override
    public AsyncResult<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        future.whenComplete(action);
        return this;
    }

    @Override
    public AsyncResult<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        future.whenCompleteAsync(action, executor);
        return this;
    }

    @Override
    public T await() {
        return future.join();
    }

}
