package dtm.di.prototypes.actions.impl;

import dtm.di.prototypes.actions.ToolChain;
import dtm.di.prototypes.actions.ThrowableConsumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DefaultToolChain<T> implements ToolChain<T> {

    private final List<ThrowableConsumer<? super T>> actions = new ArrayList<>();

    private ThrowableConsumer<ErrorContext<T>> errorHandler;

    @Override
    public ToolChain<T> add(ThrowableConsumer<? super T> action) {
        actions.add(Objects.requireNonNull(action));
        return this;
    }

    @Override
    public ToolChain<T> addAll(ToolChain<? super T> chain) {
        Objects.requireNonNull(chain);
        actions.add(chain::execute);
        return this;
    }

    @Override
    public ToolChain<T> ifError(ThrowableConsumer<ErrorContext<T>> handler) {
        Objects.requireNonNull(handler);

        if (this.errorHandler == null) {
            this.errorHandler = handler;
        } else {
            this.errorHandler = this.errorHandler.andThen(handler);
        }

        return this;
    }

    @Override
    public void execute(T context) throws Throwable {
        for (int i = 0; i < actions.size(); i++) {
            ThrowableConsumer<? super T> action = actions.get(i);

            try {
                action.accept(context);
            } catch (Throwable throwable) {
                if (errorHandler == null) {
                    throw throwable;
                }

                errorHandler.accept(new ErrorContext<>(
                        context,
                        action,
                        i,
                        throwable
                ));

                return;
            }
        }
    }
}