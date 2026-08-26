package dtm.di.settings;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ContainerDefaults {

    private ContainerDefaults() {}

    public static boolean isContainer(Class<?> type) {
        if (type == null) return false;
        return type.isArray()
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type);
    }

    public static Object newEmpty(Class<?> type) {
        if (type == null) return null;

        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }

        if (!isContainer(type)) return null;

        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            Object concrete = newConcrete(type);
            if (concrete != null) return concrete;
        }

        if (Map.class.isAssignableFrom(type)) {
            if (ConcurrentMap.class.isAssignableFrom(type)) return new ConcurrentHashMap<>();
            if (SortedMap.class.isAssignableFrom(type) || NavigableMap.class.isAssignableFrom(type)) {
                return new TreeMap<>();
            }
            return new LinkedHashMap<>();
        }

        if (SortedSet.class.isAssignableFrom(type) || NavigableSet.class.isAssignableFrom(type)) {
            return new TreeSet<>();
        }
        if (Set.class.isAssignableFrom(type)) return new LinkedHashSet<>();
        if (Deque.class.isAssignableFrom(type) || Queue.class.isAssignableFrom(type)) return new ArrayDeque<>();
        if (List.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) return new ArrayList<>();

        return null;
    }

    public static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] upper = wildcardType.getUpperBounds();
            if (upper.length > 0) return rawClass(upper[0]);
        }
        return null;
    }

    public static Type firstTypeArgument(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            if (arguments.length > 0) {
                Type argument = arguments[0];
                if (argument instanceof WildcardType wildcardType) {
                    Type[] upper = wildcardType.getUpperBounds();
                    return upper.length > 0 ? upper[0] : Object.class;
                }
                return argument;
            }
        }
        return Object.class;
    }

    private static Object newConcrete(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            if (!constructor.canAccess(null)) constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }
}
