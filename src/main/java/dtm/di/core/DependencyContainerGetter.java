package dtm.di.core;

import dtm.di.exceptions.NewInstanceException;
import dtm.di.prototypes.Dependency;

import java.util.List;

public interface DependencyContainerGetter {
    <T> T getDependency(Class<T> reference);
    <T> T getDependency(Class<T> reference, String qualifier);

    <T> T newInstance(Class<T> referenceClass) throws NewInstanceException;

    void injectDependencies(Object instance);

    List<Dependency> getRegisteredDependencies();
}
