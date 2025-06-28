package dtm.di.storage.lazy;

import dtm.di.exceptions.LazyDependencyException;
import dtm.di.prototypes.AsyncLazyDependency;
import dtm.di.prototypes.LazyDependency;
import lombok.SneakyThrows;

import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

public class Lazy {

    public static <T> LazyDependency<T> of(Supplier<T> supplier) {
        return new LazyDependency<>() {
            private T dependency;

            @Override
            public T get() {
                if(dependency == null){
                    dependency = supplier.get();
                }
                return dependency;
            }

            @Override
            public boolean isPresent() {
                if (dependency == null) {
                    dependency = supplier.get();
                }
                return dependency != null;
            }


            @Override
            public T awaitOrNull(long timeout, TimeUnit unit) {
                long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
                long wait = 5;

                while (System.currentTimeMillis() < deadline) {
                    T value  = get();
                    if (value != null) return value;

                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(wait));
                    wait = Math.min(wait * 2, 100);
                }

                return null;
            }

            @Override
            public T awaitOrThrow(long timeout, TimeUnit unit) throws LazyDependencyException {
                T value = awaitOrNull(timeout, unit);
                if(value != null) return value;
                throw new LazyDependencyException("Dependecia não carregada.");
            }
        };
    }

    public static <T> LazyDependency<T> ofAsync(Supplier<CompletableFuture<T>> supplier){
        return new AsyncLazyDependency<>() {

            private CompletableFuture<T> dependencyAsync;

            @Override
            public void load() {
                ensureLoaded();
            }

            @Override
            public CompletableFuture<T> ensureLoaded() {
                return loadIfNeeded();
            }

            @Override
            public T get() {
                try {
                    return ensureLoaded().get();
                } catch (InterruptedException | CancellationException | ExecutionException e) {
                    return null;
                }
            }

            @Override
            public boolean isPresent() {
                CompletableFuture<T> future = ensureLoaded();
                return future != null && future.isDone();
            }


            @Override
            public T awaitOrNull(long timeout, TimeUnit unit) {
                try{
                    return ensureLoaded().get(timeout, unit);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    return null;
                }
            }

            @Override
            public T awaitOrThrow(long timeout, TimeUnit unit) throws LazyDependencyException {
                T value = awaitOrNull(timeout, unit);
                if(value != null) return value;
                throw new LazyDependencyException("Dependecia não carregada.");
            }

            private synchronized CompletableFuture<T> loadIfNeeded() {
                if (dependencyAsync == null) {
                    dependencyAsync = supplier.get();
                }
                return dependencyAsync;
            }

        };
    }

}
