package dtm.di.core;

import dtm.di.exceptions.InvalidClassRegistrationException;

public interface DependencyContainer extends
        DependencyContainerGetter,
        DependencyContainerRegistor,
        DependencyContainerConfigurator
{
    void load() throws InvalidClassRegistrationException;
    void unload();
    boolean isLoaded();
    void loadDirectory(String path);
}
