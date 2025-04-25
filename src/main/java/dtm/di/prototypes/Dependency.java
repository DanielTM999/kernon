package dtm.di.prototypes;

import java.util.List;

public abstract class Dependency {
    public abstract boolean isSingleton();
    public abstract Object getDependency();
    public abstract Class<?> getDependencyClass();
    public abstract String getQualifier();
    public abstract List<Class<?>> getDependencyClassInstanceTypes();
}
