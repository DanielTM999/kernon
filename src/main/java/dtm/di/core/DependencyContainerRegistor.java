package dtm.di.core;

import dtm.di.exceptions.InvalidClassRegistrationException;

public interface DependencyContainerRegistor {
    void registerDependency(Object dependency, String qualifier) throws InvalidClassRegistrationException;
    void registerDependency(Object dependency) throws InvalidClassRegistrationException;

    void unRegisterDependency(Class<?> dependency);
}
