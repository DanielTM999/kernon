package dtm.di.prototypes.actions;

import java.util.Objects;

@FunctionalInterface
public interface ThrowableBiConsumer<T, U> {

    void accept(T t, U u) throws Throwable;

    default ThrowableBiConsumer<T, U> andThen(ThrowableBiConsumer<? super T, ? super U> after) {
        Objects.requireNonNull(after);

        return (t, u) -> {
            accept(t, u);
            after.accept(t, u);
        };
    }
}
