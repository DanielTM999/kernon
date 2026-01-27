package dtm.di.storage.async;

import dtm.di.prototypes.async.AsyncComponent;
import dtm.di.prototypes.async.AsyncResult;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;

public class AsyncComponentStorage<T> implements AsyncComponent<T> {

    private final Class<T> referenceClass;
    private final String qualifier;
    private final CompletableFuture<T> action;

    public AsyncComponentStorage(Class<?> referenceClass, String qualifier, CompletableFuture<?> resolveComponentAsync) {
        this.referenceClass = (Class<T>) referenceClass;
        this.qualifier = qualifier;
        this.action = (CompletableFuture<T>) resolveComponentAsync;
    }

    @Override
    public @NonNull AsyncResult<T> getAsync() {
        return new AsyncResultWrapper<>(action);
    }

    @Override
    public @NonNull Class<T> getReferenceClass() {
        return referenceClass;
    }

    @Override
    public @NonNull String getQualifier() {
        return qualifier;
    }
}
