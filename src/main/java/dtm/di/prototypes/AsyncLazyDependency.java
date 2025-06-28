package dtm.di.prototypes;

import java.util.concurrent.CompletableFuture;

public interface AsyncLazyDependency<T> extends LazyDependency<T>{
    void load();
    CompletableFuture<T> ensureLoaded();
}
