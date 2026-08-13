package dtm.di.storage.external;

import dtm.di.event.EventListenerRegistration;
import dtm.di.prototypes.Dependency;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public final class ExternalComponentRegistration {

    private final Class<?> ownerClass;
    private final long sequence;

    private final List<DependencyRegistrationSlot> dependencySlots = Collections.synchronizedList(new ArrayList<>());
    private final Set<Class<?>> dependencies = Collections.synchronizedSet(new LinkedHashSet<>());
    private final List<Object> singletonInstances = Collections.synchronizedList(new ArrayList<>());
    private final List<EventListenerRegistration> eventListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<CompletableFuture<?>> asyncTasks = Collections.synchronizedList(new ArrayList<>());
    private final Set<Class<?>> reflectionCacheClasses = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<Class<?>> proxyCacheClasses = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<Class<?>, Dependency> primaryTypes = Collections.synchronizedMap(new LinkedHashMap<>());

    private final AtomicBoolean active = new AtomicBoolean(true);

    public ExternalComponentRegistration(Class<?> ownerClass, long sequence) {
        this.ownerClass = ownerClass;
        this.sequence = sequence;
    }

    public boolean isActive() {
        return active.get();
    }

    public void deactivate() {
        active.set(false);
    }

    public void addDependencies(Collection<Class<?>> classes) {
        if (classes == null) {
            return;
        }
        for (Class<?> clazz : classes) {
            if (clazz != null && clazz != ownerClass) {
                dependencies.add(clazz);
            }
        }
    }

    public void addSlot(DependencyRegistrationSlot slot) {
        if (slot != null) {
            dependencySlots.add(slot);
        }
    }

    public void addPrimaryType(Class<?> type, Dependency dependency) {
        if (type != null && dependency != null) {
            primaryTypes.putIfAbsent(type, dependency);
        }
    }

    public void addSingletonInstance(Object instance) {
        if (instance == null) {
            return;
        }
        synchronized (singletonInstances) {
            for (Object registered : singletonInstances) {
                if (registered == instance) {
                    return;
                }
            }
            singletonInstances.add(instance);
        }
    }

    public boolean addEventListener(EventListenerRegistration registration) {
        if (registration == null) {
            return false;
        }

        synchronized (eventListeners) {
            if (!active.get()) {
                return false;
            }
            eventListeners.add(registration);
            return true;
        }
    }

    public void addAsyncTask(CompletableFuture<?> task) {
        if (task != null) {
            asyncTasks.add(task);
        }
    }

    public void addReflectionCacheClass(Class<?> clazz) {
        if (clazz != null) {
            reflectionCacheClasses.add(clazz);
        }
    }

    public void addProxyCacheClass(Class<?> clazz) {
        if (clazz != null) {
            proxyCacheClasses.add(clazz);
        }
    }

    public Set<Class<?>> snapshotDependencies() {
        synchronized (dependencies) {
            return new LinkedHashSet<>(dependencies);
        }
    }

    public List<DependencyRegistrationSlot> snapshotSlots() {
        synchronized (dependencySlots) {
            return new ArrayList<>(dependencySlots);
        }
    }

    public List<Object> snapshotInstances() {
        synchronized (singletonInstances) {
            return new ArrayList<>(singletonInstances);
        }
    }

    public List<EventListenerRegistration> snapshotEventListeners() {
        synchronized (eventListeners) {
            return new ArrayList<>(eventListeners);
        }
    }

    public List<CompletableFuture<?>> snapshotAsyncTasks() {
        synchronized (asyncTasks) {
            return new ArrayList<>(asyncTasks);
        }
    }

    public Set<Class<?>> snapshotReflectionCacheClasses() {
        synchronized (reflectionCacheClasses) {
            return new LinkedHashSet<>(reflectionCacheClasses);
        }
    }

    public Set<Class<?>> snapshotProxyCacheClasses() {
        synchronized (proxyCacheClasses) {
            return new LinkedHashSet<>(proxyCacheClasses);
        }
    }

    public Map<Class<?>, Dependency> snapshotPrimaryTypes() {
        synchronized (primaryTypes) {
            return new LinkedHashMap<>(primaryTypes);
        }
    }

    public Set<ClassLoader> classLoaders() {
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
        collectLoader(loaders, ownerClass);
        for (DependencyRegistrationSlot slot : snapshotSlots()) {
            collectLoader(loaders, slot.indexedType());
            collectLoader(loaders, slot.dependency() != null ? slot.dependency().getDependencyClass() : null);
        }
        for (Class<?> clazz : snapshotReflectionCacheClasses()) {
            collectLoader(loaders, clazz);
        }
        return loaders;
    }

    public void clear() {
        dependencySlots.clear();
        dependencies.clear();
        singletonInstances.clear();
        eventListeners.clear();
        asyncTasks.clear();
        reflectionCacheClasses.clear();
        proxyCacheClasses.clear();
        primaryTypes.clear();
    }

    private void collectLoader(Set<ClassLoader> loaders, Class<?> clazz) {
        if (clazz == null) {
            return;
        }
        ClassLoader loader = clazz.getClassLoader();
        if (loader != null) {
            loaders.add(loader);
        }
    }
}
