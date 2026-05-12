package dtm.di.prototypes;

import dtm.di.prototypes.actions.ThrowableConsumer;

import java.util.Objects;
import java.util.function.Consumer;

@FunctionalInterface
public interface ThrowableAction {
    void run() throws Throwable;

    default ThrowableAction andThen(ThrowableAction after) {
        Objects.requireNonNull(after);
        return () -> { run(); after.run(); };
    }

}
