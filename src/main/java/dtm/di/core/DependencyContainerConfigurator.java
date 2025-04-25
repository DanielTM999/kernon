package dtm.di.core;

public interface DependencyContainerConfigurator {
    void enableChildrenRegistration();
    void disableChildrenRegistration();

    void enableParallelInjection();
    void disableParallelInjection();
}
