package dtm.di.prototypes.proxy;

import lombok.NonNull;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Constructor;

public class ProxyFactory {

    private final Object instance;
    private final Class<?> clazz;

    public ProxyFactory(@NonNull Object instance){
        this.instance = instance;
        this.clazz = instance.getClass();
    }

    public ProxyFactory(@NonNull Object instance, @NonNull Class<?> clazz){
        this.instance = instance;
        this.clazz = clazz;
    }

    public Object proxyObject() throws Exception {

        Constructor<?> constructor = getConstructorWithLeastParameters(clazz);
        constructor.setAccessible(true);
        Object[] args = buildDummyArgs(constructor.getParameterTypes());

        try (DynamicType.Unloaded<?> unloaded = new ByteBuddy()
                .subclass(clazz)
                .method(ElementMatchers.isDeclaredBy(clazz))
                .intercept(MethodDelegation.to(new ObjectInterceptor(instance)))
                .make()) {

            Class<?> proxyClass = unloaded
                    .load(clazz.getClassLoader())
                    .getLoaded();

            Constructor<?> proxyConstructor = proxyClass.getDeclaredConstructor(constructor.getParameterTypes());
            proxyConstructor.setAccessible(true);

            return proxyConstructor.newInstance(args);
        }
    }

    public static Object newProxyObject(@NonNull Object instance, @NonNull Class<?> clazz) throws Exception {
        ProxyFactory proxyFactory = new ProxyFactory(instance, clazz);
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
