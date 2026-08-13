package dtm.di.testsupport;

import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.prototypes.Dependency;
import dtm.di.storage.StaticContainer;
import dtm.di.storage.containers.DependencyContainerStorage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public final class ContainerFixture {

    private ContainerFixture() {
    }

    public static DependencyContainerStorage newContainer(String... profiles) {
        StaticContainer.removeDependencyContainer(DependencyContainerStorage.class);
        return DependencyContainerStorage.getInstance(MainCounter.class, profiles);
    }

    public static DependencyContainerStorage newLoadedContainer(String... profiles) throws InvalidClassRegistrationException {
        DependencyContainerStorage container = newContainer(profiles);
        container.load();
        return container;
    }

    public static void dispose(DependencyContainerStorage container) {
        if (container != null && container.isLoaded()) {
            container.unload();
        }
        StaticContainer.removeDependencyContainer(DependencyContainerStorage.class);
    }

    @SuppressWarnings("unchecked")
    public static Map<Class<?>, Map<String, Dependency>> dependencyContainerOf(DependencyContainerStorage container) {
        return (Map<Class<?>, Map<String, Dependency>>) readField(container, "dependencyContainer");
    }

    @SuppressWarnings("unchecked")
    public static Map<Class<?>, Dependency> primaryIndexOf(DependencyContainerStorage container) {
        return (Map<Class<?>, Dependency>) readField(container, "primaryDependencyIndex");
    }

    @SuppressWarnings("unchecked")
    public static Map<Class<?>, ?> externalRegistrationsOf(DependencyContainerStorage container) {
        return (Map<Class<?>, ?>) readField(container, "externalComponentRegistrations");
    }

    public static boolean isReflectionCached(Class<?> clazz) {
        return staticMapContains("dtm.di.common.reflection.ReflectionCache", "CACHE", clazz);
    }

    public static boolean isProxyCached(Class<?> clazz) {
        return staticMapContains("dtm.di.prototypes.proxy.ProxyFactory", "proxyCache", clazz);
    }

    public static Object invoke(Object target, String methodName, Object... args) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
            throw new IllegalStateException("metodo nao encontrado: " + methodName + " em " + target.getClass());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("falha ao invocar " + methodName, e);
        }
    }

    private static boolean staticMapContains(String className, String fieldName, Class<?> key) {
        try {
            Class<?> holder = Class.forName(className);
            Field field = holder.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof Map<?, ?> map && map.containsKey(key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("falha ao inspecionar " + className + "#" + fieldName, e);
        }
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("falha ao ler o campo " + fieldName, e);
        }
    }
}
