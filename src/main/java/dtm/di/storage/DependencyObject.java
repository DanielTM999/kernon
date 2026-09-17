package dtm.di.storage;

import dtm.di.common.reflection.GenericTypes;
import dtm.di.prototypes.Dependency;
import lombok.*;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

@Data
@ToString
@Builder
@EqualsAndHashCode(callSuper = false)
public class DependencyObject extends Dependency {
    private Class<?> dependencyClass;
    private String qualifier;
    private boolean singleton;

    @ToString.Exclude
    private Supplier<?> creatorFunction;

    @ToString.Exclude
    private Object singletonInstance;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Type declaredGenericType;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile Set<Type> genericSupertypesCache;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile Set<String> genericTypeKeysCache;

    public DependencyObject(
            Class<?> dependencyClass,
            String qualifier,
            boolean singleton,
            Supplier<?> creatorFunction,
            Object singletonInstance
    ) {
        this(dependencyClass, qualifier, singleton, creatorFunction, singletonInstance, null, null, null);
    }

    public DependencyObject(
            Class<?> dependencyClass,
            String qualifier,
            boolean singleton,
            Supplier<?> creatorFunction,
            Object singletonInstance,
            Type declaredGenericType
    ) {
        this(dependencyClass, qualifier, singleton, creatorFunction, singletonInstance, declaredGenericType, null, null);
    }

    public DependencyObject(
            Class<?> dependencyClass,
            String qualifier,
            boolean singleton,
            Supplier<?> creatorFunction,
            Object singletonInstance,
            Type declaredGenericType,
            Set<Type> genericSupertypesCache,
            Set<String> genericTypeKeysCache
    ) {
        this.dependencyClass = dependencyClass;
        this.qualifier = qualifier;
        this.singleton = singleton;
        this.creatorFunction = creatorFunction;
        this.singletonInstance = singletonInstance;
        this.declaredGenericType = declaredGenericType;
        this.genericSupertypesCache = genericSupertypesCache;
        this.genericTypeKeysCache = genericTypeKeysCache;
    }


    @Override
    public Object getDependency() {
        if(singleton){
            return singletonInstance;
        }
        return creatorFunction.get();
    }

    @Override
    public List<Class<?>> getDependencyClassInstanceTypes() {
        List<Class<?>> classes = new ArrayList<>();
        if (dependencyClass.equals(Object.class) || dependencyClass.isInterface()) {
            return List.of();
        }
        Class<?> superClass = dependencyClass.getSuperclass();
        Class<?>[] interfaces = dependencyClass.getInterfaces();

        if (superClass != null && !superClass.equals(Object.class)) {
            classes.add(superClass);
        }

        for(Class<?> interfaceObj : interfaces){
            if (!interfaceObj.equals(Object.class)) {
                classes.add(interfaceObj);
            }
        }
        classes.add(dependencyClass);

        return classes;
    }

    @Override
    public Set<Type> getGenericSupertypes() {
        Set<Type> cached = genericSupertypesCache;
        if (cached != null) {
            return cached;
        }

        Set<Type> resolved = new LinkedHashSet<>(GenericTypes.supertypes(dependencyClass));
        if (declaredGenericType != null) {
            resolved.addAll(GenericTypes.supertypes(declaredGenericType));
        }

        Set<Type> immutable = Collections.unmodifiableSet(resolved);
        genericSupertypesCache = immutable;
        return immutable;
    }

    @Override
    public Set<String> getGenericTypeKeys() {
        Set<String> cached = genericTypeKeysCache;
        if (cached != null) {
            return cached;
        }

        Set<String> keys = GenericTypes.keysOf(getGenericSupertypes());
        genericTypeKeysCache = keys;
        return keys;
    }
}
