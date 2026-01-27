package dtm.di.prototypes.async;

import dtm.di.prototypes.ComponentActionRegistry;
import lombok.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public interface AsyncComponent<T> extends ComponentActionRegistry<T>{

    @NonNull
    AsyncResult<T> getAsync();

}
