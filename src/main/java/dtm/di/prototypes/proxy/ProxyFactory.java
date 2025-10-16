package dtm.di.prototypes.proxy;

import dtm.di.annotations.aop.ProxyInstance;
import dtm.di.core.DependencyContainer;
import lombok.NonNull;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyFactory {

    private static final Map<Class<?>, Class<?>> proxyCache = new ConcurrentHashMap<>();

    private final DependencyContainer dependencyContainer;
    private final Object instance;
    private final Class<?> clazz;

    public ProxyFactory(@NonNull Object instance, DependencyContainer dependencyContainer){
        this.instance = instance;
        this.clazz = instance.getClass();
        this.dependencyContainer = dependencyContainer;
    }

    public ProxyFactory(@NonNull Object instance, @NonNull Class<?> clazz, DependencyContainer dependencyContainer){
        this.instance = instance;
        this.clazz = clazz;
        this.dependencyContainer = dependencyContainer;
    }

    public Object proxyObject() throws Exception {
        String interceptorFieldName = "___interceptor";
        Constructor<?> constructor = getConstructorWithLeastParameters(clazz);
        constructor.setAccessible(true);
        Object[] args = buildDummyArgs(constructor.getParameterTypes());


        Class<?> proxyClass = proxyCache.computeIfAbsent(clazz, cls -> {
            AnnotationDescription proxyAnnotation = AnnotationDescription.Builder
                    .ofType(ProxyInstance.class)
                    .build();
            try (DynamicType.Unloaded<?> unloaded = new ByteBuddy()
                    .subclass(cls)
                    .defineField(interceptorFieldName, ObjectInterceptor.class)
                    .method(ElementMatchers.isDeclaredBy(cls))
                    .intercept(MethodDelegation.toField(interceptorFieldName))
                    .annotateType(proxyAnnotation)
                    .make()) {

                return unloaded
                        .load(cls.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                        .getLoaded();

            } catch (Exception e) {
                throw new RuntimeException("Erro ao criar proxy para " + cls, e);
            }
        });

        Constructor<?> proxyConstructor = proxyClass.getDeclaredConstructor(constructor.getParameterTypes());
        proxyConstructor.setAccessible(true);
        Object proxyInstance = proxyConstructor.newInstance(args);
        Field interceptorField = proxyClass.getDeclaredField(interceptorFieldName);
        interceptorField.setAccessible(true);
        interceptorField.set(proxyInstance, new ObjectInterceptor(instance, dependencyContainer));

        return proxyInstance;
    }

    public static Object newProxyObject(@NonNull Object instance, @NonNull Class<?> clazz, DependencyContainer dependencyContainer) throws Exception {
        ProxyFactory proxyFactory = new ProxyFactory(instance, clazz, dependencyContainer);
        return proxyFactory.proxyObject();
    }

    private Constructor<?> getConstructorWithLeastParameters(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();

        if (constructors.length == 0) {
            try {
                return clazz.getConstructor();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("A classe não possui construtores acessíveis", e);
            }
        }

        Constructor<?> selected = constructors[0];

        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() < selected.getParameterCount()) {
                selected = constructor;
            }
        }

        return selected;
    }


    private Object[] buildDummyArgs(Class<?>[] paramTypes) {
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> type = paramTypes[i];

            if (type.isPrimitive()) {
                if (type == boolean.class) args[i] = false;
                else if (type == byte.class) args[i] = (byte) 0;
                else if (type == short.class) args[i] = (short) 0;
                else if (type == int.class) args[i] = 0;
                else if (type == long.class) args[i] = 0L;
                else if (type == float.class) args[i] = 0f;
                else if (type == double.class) args[i] = 0d;
                else if (type == char.class) args[i] = '\u0000';
            } else {
                args[i] = null;
            }
        }

        return args;
    }

}
