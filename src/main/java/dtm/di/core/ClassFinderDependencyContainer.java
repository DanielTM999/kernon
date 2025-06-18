package dtm.di.core;

import dtm.discovery.core.ClassFinderConfigurations;

public interface ClassFinderDependencyContainer extends DependencyContainer {
    ClassFinderConfigurations getClassFinderConfigurations();
}
