package dtm.di.common.reflection;

import dtm.di.settings.ContainerDefaults;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class GenericTypes {

    public static final String UNRESOLVED = "?";

    private static final int SUBSTITUTION_DEPTH_LIMIT = 32;

    private static final ConcurrentMap<Class<?>, Set<Type>> SUPERTYPES = new ConcurrentHashMap<>();

    private GenericTypes() {
        throw new IllegalStateException("utility class");
    }

    public static Class<?> raw(Type type) {
        if (type instanceof GenericArrayType genericArray) {
            Class<?> component = raw(genericArray.getGenericComponentType());
            return (component != null) ? Array.newInstance(component, 0).getClass() : null;
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            Type[] bounds = typeVariable.getBounds();
            return (bounds.length > 0) ? raw(bounds[0]) : Object.class;
        }
        return ContainerDefaults.rawClass(type);
    }

    public static String key(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getName();
        }
        if (type instanceof ParameterizedType parameterized) {
            String arguments = Arrays.stream(parameterized.getActualTypeArguments())
                    .map(GenericTypes::key)
                    .collect(Collectors.joining(","));
            return key(parameterized.getRawType()) + "<" + arguments + ">";
        }
        if (type instanceof GenericArrayType genericArray) {
            return key(genericArray.getGenericComponentType()) + "[]";
        }
        return UNRESOLVED;
    }

    public static boolean isFullyResolved(String key) {
        return key != null && !key.contains(UNRESOLVED);
    }

    public static boolean isParameterized(Type type) {
        return type instanceof ParameterizedType;
    }

    public static Set<Type> supertypes(Class<?> implementation) {
        if (implementation == null) {
            return Set.of();
        }
        return SUPERTYPES.computeIfAbsent(implementation, GenericTypes::walkSupertypes);
    }

    public static Set<Type> supertypes(Type declaredType) {
        if (declaredType == null) {
            return Set.of();
        }
        if (declaredType instanceof Class<?> clazz) {
            return supertypes(clazz);
        }

        Set<Type> collected = new LinkedHashSet<>();
        collect(declaredType, Map.of(), collected, new LinkedHashSet<>());
        return Collections.unmodifiableSet(collected);
    }

    public static Set<String> keysOf(Collection<Type> types) {
        if (types == null || types.isEmpty()) {
            return Set.of();
        }
        return types.stream().map(GenericTypes::key).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean matches(Type requested, Type candidate) {
        if (requested == null || candidate == null) {
            return false;
        }
        if (requested instanceof WildcardType) {
            return matchesArgument(requested, candidate);
        }
        if (requested instanceof Class<?> requestedClass) {
            Class<?> candidateRaw = raw(candidate);
            return candidateRaw != null && requestedClass.isAssignableFrom(candidateRaw);
        }
        if (!(requested instanceof ParameterizedType requestedParameterized)) {
            return false;
        }

        Class<?> requestedRaw = raw(requestedParameterized);
        Class<?> candidateRaw = raw(candidate);
        if (requestedRaw == null || candidateRaw == null || !requestedRaw.isAssignableFrom(candidateRaw)) {
            return false;
        }
        if (!(candidate instanceof ParameterizedType candidateParameterized)) {
            return false;
        }

        Type[] requestedArguments = requestedParameterized.getActualTypeArguments();
        Type[] candidateArguments = candidateParameterized.getActualTypeArguments();
        if (requestedArguments.length != candidateArguments.length) {
            return false;
        }

        for (int index = 0; index < requestedArguments.length; index++) {
            if (!matchesArgument(requestedArguments[index], candidateArguments[index])) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasWildcard(Type type) {
        if (type instanceof WildcardType || type instanceof TypeVariable<?>) {
            return true;
        }
        if (type instanceof GenericArrayType genericArray) {
            return hasWildcard(genericArray.getGenericComponentType());
        }
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (hasWildcard(argument)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void clear() {
        SUPERTYPES.clear();
    }

    public static void clear(Class<?> clazz) {
        if (clazz == null) {
            return;
        }
        SUPERTYPES.remove(clazz);
    }

    public static void clear(Collection<Class<?>> classes) {
        if (classes == null) {
            return;
        }
        for (Class<?> clazz : classes) {
            clear(clazz);
        }
    }

    private static boolean matchesArgument(Type requested, Type candidate) {
        if (requested instanceof WildcardType wildcard) {
            Class<?> candidateRaw = raw(candidate);
            if (candidateRaw == null) {
                return false;
            }
            for (Type upperBound : wildcard.getUpperBounds()) {
                Class<?> bound = raw(upperBound);
                if (bound == null || !bound.isAssignableFrom(candidateRaw)) {
                    return false;
                }
            }
            for (Type lowerBound : wildcard.getLowerBounds()) {
                Class<?> bound = raw(lowerBound);
                if (bound == null || !candidateRaw.isAssignableFrom(bound)) {
                    return false;
                }
            }
            return true;
        }

        if (requested instanceof ParameterizedType && hasWildcard(requested)) {
            return matches(requested, candidate);
        }

        return key(requested).equals(key(candidate));
    }

    private static Set<Type> walkSupertypes(Class<?> implementation) {
        Set<Type> collected = new LinkedHashSet<>();
        collect(implementation, Map.of(), collected, new LinkedHashSet<>());
        return Collections.unmodifiableSet(collected);
    }

    private static void collect(Type type, Map<TypeVariable<?>, Type> bindings, Set<Type> collected, Set<String> visited) {
        if (type == null) {
            return;
        }

        Class<?> rawType;
        Map<TypeVariable<?>, Type> nextBindings;
        Type resolvedNode;

        if (type instanceof ParameterizedType parameterized) {
            Type resolved = substitute(parameterized, bindings, 0);
            if (!(resolved instanceof ParameterizedType resolvedParameterized)) {
                return;
            }
            collected.add(resolvedParameterized);
            rawType = raw(resolvedParameterized);
            nextBindings = bindingsOf(resolvedParameterized);
            resolvedNode = resolvedParameterized;
        } else if (type instanceof Class<?> clazz) {
            rawType = clazz;
            nextBindings = Map.of();
            resolvedNode = clazz;
        } else {
            return;
        }

        if (rawType == null || Object.class.equals(rawType)) {
            return;
        }
        if (!visited.add(key(resolvedNode))) {
            return;
        }

        collect(rawType.getGenericSuperclass(), nextBindings, collected, visited);
        for (Type interfaceType : rawType.getGenericInterfaces()) {
            collect(interfaceType, nextBindings, collected, visited);
        }
    }

    private static Map<TypeVariable<?>, Type> bindingsOf(ParameterizedType parameterized) {
        Class<?> rawType = raw(parameterized);
        if (rawType == null) {
            return Map.of();
        }

        TypeVariable<?>[] parameters = rawType.getTypeParameters();
        Type[] arguments = parameterized.getActualTypeArguments();
        if (parameters.length == 0 || parameters.length != arguments.length) {
            return Map.of();
        }

        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (int index = 0; index < parameters.length; index++) {
            bindings.put(parameters[index], arguments[index]);
        }
        return bindings;
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings, int depth) {
        if (type == null || bindings.isEmpty() || depth > SUBSTITUTION_DEPTH_LIMIT) {
            return type;
        }

        if (type instanceof TypeVariable<?> typeVariable) {
            Type bound = bindings.get(typeVariable);
            if (bound == null || bound.equals(typeVariable)) {
                return typeVariable;
            }
            return substitute(bound, bindings, depth + 1);
        }

        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] substituted = new Type[arguments.length];
            boolean changed = false;
            for (int index = 0; index < arguments.length; index++) {
                substituted[index] = substitute(arguments[index], bindings, depth + 1);
                changed = changed || substituted[index] != arguments[index];
            }
            if (!changed) {
                return parameterized;
            }
            return new ResolvedParameterizedType(parameterized.getRawType(), substituted, parameterized.getOwnerType());
        }

        if (type instanceof GenericArrayType genericArray) {
            Type component = substitute(genericArray.getGenericComponentType(), bindings, depth + 1);
            if (component == genericArray.getGenericComponentType()) {
                return genericArray;
            }
            return new ResolvedGenericArrayType(component);
        }

        if (type instanceof WildcardType wildcard) {
            return new ResolvedWildcardType(
                    substituteAll(wildcard.getUpperBounds(), bindings, depth),
                    substituteAll(wildcard.getLowerBounds(), bindings, depth)
            );
        }

        return type;
    }

    private static Type[] substituteAll(Type[] types, Map<TypeVariable<?>, Type> bindings, int depth) {
        Type[] substituted = new Type[types.length];
        for (int index = 0; index < types.length; index++) {
            substituted[index] = substitute(types[index], bindings, depth + 1);
        }
        return substituted;
    }

    private static final class ResolvedParameterizedType implements ParameterizedType {
        private final Type rawType;
        private final Type[] actualTypeArguments;
        private final Type ownerType;

        private ResolvedParameterizedType(Type rawType, Type[] actualTypeArguments, Type ownerType) {
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments;
            this.ownerType = ownerType;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ParameterizedType that)) return false;
            return Objects.equals(rawType, that.getRawType())
                    && Objects.equals(ownerType, that.getOwnerType())
                    && Arrays.equals(actualTypeArguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(actualTypeArguments) ^ Objects.hashCode(ownerType) ^ Objects.hashCode(rawType);
        }

        @Override
        public String toString() {
            return key(this);
        }
    }

    private static final class ResolvedGenericArrayType implements GenericArrayType {
        private final Type genericComponentType;

        private ResolvedGenericArrayType(Type genericComponentType) {
            this.genericComponentType = genericComponentType;
        }

        @Override
        public Type getGenericComponentType() {
            return genericComponentType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof GenericArrayType that)) return false;
            return Objects.equals(genericComponentType, that.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(genericComponentType);
        }

        @Override
        public String toString() {
            return key(this);
        }
    }

    private static final class ResolvedWildcardType implements WildcardType {
        private final Type[] upperBounds;
        private final Type[] lowerBounds;

        private ResolvedWildcardType(Type[] upperBounds, Type[] lowerBounds) {
            this.upperBounds = upperBounds;
            this.lowerBounds = lowerBounds;
        }

        @Override
        public Type[] getUpperBounds() {
            return upperBounds.clone();
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBounds.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WildcardType that)) return false;
            return Arrays.equals(upperBounds, that.getUpperBounds())
                    && Arrays.equals(lowerBounds, that.getLowerBounds());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(upperBounds) ^ Arrays.hashCode(lowerBounds);
        }

        @Override
        public String toString() {
            return UNRESOLVED;
        }
    }
}
