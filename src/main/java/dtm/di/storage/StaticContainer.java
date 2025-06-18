package dtm.di.storage;

import dtm.di.core.DependencyContainer;
import dtm.di.storage.containers.DependencyContainerStorage;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StaticContainer {

    private static final Map<Class<? extends DependencyContainer>, DependencyContainer> CONTAINERS = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T extends DependencyContainer> T getDependencyContainer(Class<T> aClass){
        return (T)CONTAINERS.get(aClass);
    }

    public static <T extends DependencyContainer> T trySetDependencyContainer(T container){
        if(container != null){
            CONTAINERS.computeIfAbsent(container.getClass(), k -> container);
        }
        return container;
    }

    public static <T extends DependencyContainer> void removeDependencyContainer(Class<? extends DependencyContainer> aClass) {
        CONTAINERS.remove(aClass);
    }

}
