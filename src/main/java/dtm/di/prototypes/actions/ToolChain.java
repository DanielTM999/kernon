package dtm.di.prototypes.actions;

import dtm.di.prototypes.actions.impl.DefaultToolChain;

public interface ToolChain<T> {
    ToolChain<T> add(ThrowableConsumer<? super T> action);
    ToolChain<T> addAll(ToolChain<? super T> chain);
    ToolChain<T> ifError(ThrowableConsumer<ErrorContext<T>> errorHandler);
    void execute(T context) throws Throwable;

    record ErrorContext<T>(
            T value,
            ThrowableConsumer<? super T> step,
            int stepIndex,
            Throwable error
    ) {}

    static <T> ToolChain<T> of() {
        return new DefaultToolChain<>();
    }

    @SafeVarargs
    static <T> ToolChain<T> of(ThrowableConsumer<? super T>... actions) {
        ToolChain<T> chain = new DefaultToolChain<>();

        if (actions != null) {
            for (ThrowableConsumer<? super T> action : actions) {
                chain.add(action);
            }
        }

        return chain;
    }

}
