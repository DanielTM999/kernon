package dtm.di.common.reflection;

import dtm.di.prototypes.CompositeDependency;
import dtm.di.prototypes.LazyDependency;
import dtm.di.prototypes.async.AsyncComponent;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class WrapperTypes {

    private static final Set<Class<?>> WRAPPERS = Set.of(
            LazyDependency.class,
            AsyncComponent.class,
            CompositeDependency.class,
            AtomicReference.class,
            WeakReference.class,
            SoftReference.class,
            List.class,
            Set.class,
            Collection.class
    );

    private static final Set<Class<?>> BEAN_COLLECTIONS = Set.of(
            List.class,
            Set.class,
            Collection.class
    );

    private WrapperTypes() {
        throw new IllegalStateException("utility class");
    }

    public static boolean isWrapper(Class<?> rawType) {
        return rawType != null && WRAPPERS.contains(rawType);
    }

    private static final Set<Class<?>> EAGER_WRAPPERS = Set.of(
            CompositeDependency.class,
            AtomicReference.class,
            WeakReference.class,
            SoftReference.class,
            List.class,
            Set.class,
            Collection.class
    );

    public static boolean isBeanCollection(Class<?> rawType) {
        return rawType != null && BEAN_COLLECTIONS.contains(rawType);
    }

    public static boolean isEagerWrapper(Class<?> rawType) {
        return rawType != null && EAGER_WRAPPERS.contains(rawType);
    }

    public static boolean isBeanMap(Type type) {
        if (!(type instanceof ParameterizedType parameterized)) return false;
        if (!Map.class.equals(parameterized.getRawType())) return false;

        Type[] arguments = parameterized.getActualTypeArguments();
        return arguments.length == 2 && String.class.equals(arguments[0]);
    }

    public static Type beanMapValueType(Type type) {
        return ((ParameterizedType) type).getActualTypeArguments()[1];
    }

    public static Set<Class<?>> all() {
        return WRAPPERS;
    }
}
