package dtm.di.settings.async;

import lombok.NonNull;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

public interface AsyncExecutorFactory {

    ExecutorService getExecutor();

    static AsyncExecutorFactory ofSingleton(@NonNull ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return () -> executor;
    }
}
