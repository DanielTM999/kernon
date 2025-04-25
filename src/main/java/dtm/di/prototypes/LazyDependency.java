package dtm.di.prototypes;

import dtm.di.exceptions.LazyDependencyException;

import java.util.concurrent.TimeUnit;

public interface LazyDependency<T> {
    T get();
    T awaitOrNull(long timeout, TimeUnit unit);
    T awaitOrThrow(long timeout, TimeUnit unit) throws LazyDependencyException;
    boolean isPresent();
}
