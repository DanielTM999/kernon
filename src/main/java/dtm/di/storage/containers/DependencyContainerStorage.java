package dtm.di.storage.containers;

import dtm.di.annotations.*;
import dtm.di.annotations.aop.Aspect;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.event.Event;
import dtm.di.common.AnnotationsUtils;
import dtm.di.common.reflection.ReflectionCache;
import dtm.di.event.impl.DefaultEventPublisher;
import dtm.di.event.EventPublisher;
import dtm.di.settings.AppSettings;
import dtm.di.settings.JsonAppSettings;
import dtm.di.annotations.settings.Value;
import dtm.di.core.ClassFinderDependencyContainer;
import dtm.di.core.DependencyContainer;
import dtm.di.core.InjectionStrategy;
import dtm.di.exceptions.*;
import dtm.di.prototypes.*;
import dtm.di.prototypes.async.AsyncComponent;
import dtm.di.prototypes.async.AsyncRegistrationFunction;
import dtm.di.prototypes.proxy.ProxyFactory;
import dtm.di.sort.TopologicalSorter;
import dtm.di.storage.*;
import dtm.di.storage.async.AsyncComponentStorage;
import dtm.di.storage.bean.BeanDependencyGraphBuilder;
import dtm.di.storage.bean.BeanGraph;
import dtm.di.storage.composite.CompositeDependencyStorage;
import dtm.di.storage.external.DependencyRegistrationSlot;
import dtm.di.storage.external.ExternalComponentRegistration;
import dtm.di.storage.external.ExternalLoadBatch;
import dtm.di.storage.lazy.Lazy;
import dtm.di.storage.lazy.ParamtrizedObject;
import dtm.di.event.EventListenerRegistration;
import dtm.discovery.core.ClassFinder;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.finder.simple.ClassFinderProjectService;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dtm.di.common.AnnotationsUtils.hasMetaAnnotation;
import static dtm.di.common.AnnotationsUtils.getAllFieldWithAnnotation;

@DisableAop
@Slf4j
@SuppressWarnings("unchecked")
public class DependencyContainerStorage implements DependencyContainer, ClassFinderDependencyContainer {

    private static final String INJECTION_STRATEGY_PROPERTY = "dependencyContainer.injectionStrategy";

    private final ExecutorService mainExecutor;
    private final ExecutorService mainVirtualExecutor;

    private final AtomicReference<InjectionStrategy> injectionStrategy;
    private final AtomicBoolean injectionStrategyConfiguredProgrammatically;
    private final Object injectionStrategyConfigurationLock;

    private final Map<Class<?>, Map<String, Dependency>> dependencyContainer;
    private final Map<Class<?>, Dependency> primaryDependencyIndex;
    private final ClassFinder classFinder;
    private final AtomicBoolean loaded;

    private final List<String> foldersToLoad;

    private final List<ServiceBean> serviceBeensDefinition;
    private final List<Set<ServiceBean>> serviceBeensDefinitionLayer;

    private final Set<Class<?>> loadedSystemClasses;

    private final Map<Class<?>, List<Method>> externalBeenBefore;
    private final Map<Class<?>, List<Method>> externalBeenAfter;

    private final Map<Class<?>, ExternalComponentRegistration> externalComponentRegistrations;
    private final ReentrantLock externalLock;
    private final AtomicLong externalRegistrationSequence;

    private final int thresholdConcurent = 50;

    private final Class<?> mainClass;
    private final List<String> profiles;
    private boolean childrenRegistration;
    private boolean aop;
    private final boolean processInlayer = true;

    @Getter
    @Setter
    private ClassFinderConfigurations classFinderConfigurations;

    public static DependencyContainerStorage getInstance(Class<?> mainClass, String... profiles){
        DependencyContainerStorage containerStorage = StaticContainer.getDependencyContainer(DependencyContainerStorage.class);
        if(containerStorage == null){
            return StaticContainer.trySetDependencyContainer(new DependencyContainerStorage(mainClass, profiles));
        }
        return containerStorage;
    }

    public static DependencyContainerStorage getInstanceFromArgs(Class<?> mainClass, String[] args){
        return getInstance(mainClass, resolveProfilesFromArgs(args).toArray(String[]::new));
    }

    public static DependencyContainerStorage getLoadedInstance(){
        DependencyContainerStorage containerStorage = StaticContainer.getDependencyContainer(DependencyContainerStorage.class);
        if(containerStorage == null){
            throw new UnloadError("DependencyContainerStorage unload");
        }

        return containerStorage;
    }

    public static void loadInstance(Class<?> mainClass, String... profiles){
        StaticContainer.trySetDependencyContainer(new DependencyContainerStorage(mainClass, profiles));
    }

    public static void loadInstanceFromArgs(Class<?> mainClass, String[] args){
        loadInstance(mainClass, resolveProfilesFromArgs(args).toArray(String[]::new));
    }


    private DependencyContainerStorage(Class<?> mainClass, String... profiles){
        ThreadFactory vFactory = Thread.ofVirtual()
                .name("MainVirtual-", 0)
                .factory();

        this.mainExecutor = Executors.newFixedThreadPool(
                Math.max(6, Runtime.getRuntime().availableProcessors()),
                runnable -> {
                    Thread t = new Thread(runnable);
                    t.setName("MainExecutor-Worker-" + t.hashCode());
                    t.setDaemon(true);
                    return t;
                }
        );
        this.mainVirtualExecutor = Executors.newThreadPerTaskExecutor(vFactory);
        this.dependencyContainer = new ConcurrentHashMap<>();
        this.primaryDependencyIndex = new ConcurrentHashMap<>();
        this.loaded = new AtomicBoolean(false);
        this.classFinder = new ClassFinderProjectService();
        this.childrenRegistration = false;
        this.injectionStrategy = new AtomicReference<>(InjectionStrategy.ADAPTIVE);
        this.injectionStrategyConfiguredProgrammatically = new AtomicBoolean(false);
        this.injectionStrategyConfigurationLock = new Object();
        this.foldersToLoad = new ArrayList<>();
        this.serviceBeensDefinition = Collections.synchronizedList(new ArrayList<>());
        this.loadedSystemClasses = ConcurrentHashMap.newKeySet();
        this.serviceBeensDefinitionLayer = Collections.synchronizedList(new ArrayList<>());
        this.externalBeenBefore = new LinkedHashMap<>();
        this.externalBeenAfter = new LinkedHashMap<>();
        this.externalComponentRegistrations = new ConcurrentHashMap<>();
        this.externalLock = new ReentrantLock();
        this.externalRegistrationSequence = new AtomicLong();
        this.classFinderConfigurations = getFindConfigurations();
        this.mainClass = mainClass;
        this.profiles = resolveProfiles(profiles);
    }

    private static List<String> resolveProfiles(String... profiles){
        List<String> selected = normalizeProfiles(profiles);
        if(!selected.isEmpty()) return selected;

        selected = resolveProfilesFromSettings();
        if(!selected.isEmpty()) return selected;

        return List.of("default");
    }

    public static List<String> resolveProfilesFromArgs(String[] args){
        if(args == null || args.length == 0) return List.of();

        List<String> profiles = new ArrayList<>();
        for(int i = 0; i < args.length; i++){
            String arg = args[i];
            if(arg == null || arg.isBlank()) continue;

            if(arg.startsWith("-profile=")){
                profiles.add(arg.substring("-profile=".length()));
                continue;
            }

            if(arg.startsWith("-p=")){
                profiles.add(arg.substring("-p=".length()));
                continue;
            }

            if(arg.equals("-profile") || arg.equals("-p")){
                if(i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("-")){
                    profiles.add(args[++i]);
                }
            }
        }

        return normalizeProfiles(profiles.toArray(String[]::new));
    }

    private static List<String> resolveProfilesFromSettings(){
        JsonAppSettings settings = new JsonAppSettings();

        List<String> profiles = normalizeProfiles(settings.getStringArray("profiles"));
        if(!profiles.isEmpty()) return profiles;

        profiles = normalizeProfiles(settings.getStringArray("profile"));
        if(!profiles.isEmpty()) return profiles;

        return List.of();
    }

    private static List<String> normalizeProfiles(String... profiles){
        if(profiles == null || profiles.length == 0) return List.of();

        return Arrays.stream(profiles)
                .filter(Objects::nonNull)
                .flatMap(profile -> Arrays.stream(profile.split(",")))
                .map(String::trim)
                .filter(profile -> !profile.isEmpty())
                .distinct()
                .toList();
    }

    @Override
    public void load() throws InvalidClassRegistrationException {
        try{
            if(isLoaded()) return;
            loadByPluginFolder();
            loadSystemClasses();
            injectExternalModules();
            filterServiceClass();
            filterExternalsBeens();
            selfInjection();
            loaded.set(true);
            registerExternalBeens(externalBeenBefore, null, null);
            registerAppSettingsIfAbsent();
            applyDeclarativeInjectionStrategy();
            registerEventPublisher();
            loadBeens();
            registerExternalBeens(externalBeenAfter, null, null);
            scanEventListeners();
        }catch (Exception e){
           throw new UnloadError("load error", e);
        }
    }

    @Override
    public void loadExternal(Collection<Class<?>> classes) throws InvalidClassRegistrationException {
        final Set<Class<?>> normalized = normalizeExternalClasses(classes);
        throwIfUnload();

        if(normalized.isEmpty()) return;

        externalLock.lock();
        try{
            throwIfUnload();
            loadExternalClasses(normalized);
        }finally {
            externalLock.unlock();
        }
    }

    @Override
    public void unload(Collection<Class<?>> classes) {
        final Set<Class<?>> normalized = normalizeExternalClasses(classes);
        throwIfUnload();

        if(normalized.isEmpty()) return;

        externalLock.lock();
        try{
            throwIfUnload();
            unloadExternalClasses(normalized);
        }finally {
            externalLock.unlock();
        }
    }

    /**
     * Registra o {@link EventPublisher} padrão no container e dispara o scan de
     * {@code @EventListener}. Idempotente: se um EventPublisher já foi registrado
     * (via Configuration ou registro manual), não sobrescreve.
     */
    private void registerEventPublisher(){
        try{
            Map<String, Dependency> existing = dependencyContainer.get(EventPublisher.class);
            if(existing != null && !existing.isEmpty()) return;

            DefaultEventPublisher publisher = new DefaultEventPublisher(this, mainExecutor);
            registerObject(publisher, "default", false);
        }catch (Exception e){
            log.error("Falha ao registrar EventPublisher: {}", e.getMessage(), e);
        }
    }

    /**
     * Obtém apenas os beans com listeners declarativos já carregados. O
     * EventPublisher não pode usar getInstancesByClass(Object.class) aqui,
     * pois isso tentaria criar todos os beans enquanto ele próprio ainda está
     * sendo inicializado, permitindo ciclos de injeção.
     */
    private void scanEventListeners() {
        DefaultEventPublisher publisher = getDefaultEventPublisher();
        if (publisher != null) {
            publisher.scan(getLoadedEventListeners());
        }
    }

    /**
     * Retorna somente os beans declarados para escutar eventos. Isso evita que
     * a inicialização do EventPublisher force a criação de todos os serviços
     * e aspectos registrados no container.
     */
    private List<Object> getLoadedEventListeners() {
        List<Object> listeners = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Map.Entry<Class<?>, Map<String, Dependency>> entry : dependencyContainer.entrySet()) {
            if (!AnnotationsUtils.hasMetaAnnotation(entry.getKey(), Event.class)) {
                continue;
            }

            for (Dependency dependency : entry.getValue().values()) {
                try {
                    Object instance = dependency.getDependency();
                    if (instance != null && visited.add(instance)) {
                        listeners.add(instance);
                    }
                } catch (Exception e) {
                    log.warn(
                            "Falha ao obter listener de evento '{}': {}",
                            entry.getKey().getName(),
                            e.getMessage()
                    );
                }
            }
        }

        return listeners;
    }

    @Override
    public void unload() {
        externalLock.lock();
        try{
            List<ExternalComponentRegistration> externals = externalRegistrationsInReverseOrder(
                    new ArrayList<>(externalComponentRegistrations.values())
            );

            for(ExternalComponentRegistration registration : externals){
                registration.deactivate();
            }

            cancelAsyncTasks(externals);
            unregisterEventListeners(externals);
            List<Object> shutdownInstances = collectShutdownInstances(externals);
            shutdownInstances.addAll(collectContainerSingletons());
            invokePreDestroyMethods(shutdownInstances);
            clearExternalCaches(externals);

            for(ExternalComponentRegistration registration : externals){
                registration.clear();
            }

            externalComponentRegistrations.clear();

            loaded.set(false);
            this.classFinderConfigurations = getFindConfigurations();
            loadedSystemClasses.clear();
            serviceBeensDefinition.clear();
            serviceBeensDefinitionLayer.clear();
            dependencyContainer.clear();
            primaryDependencyIndex.clear();
            foldersToLoad.clear();
            externalBeenBefore.clear();
            externalBeenAfter.clear();
        }finally {
            externalLock.unlock();
        }
    }

    /**
     * Invoca todos os métodos anotados com {@link dtm.di.annotations.PreDestroy} dos beans
     * registrados (singleton). Erros são logados e ignorados — shutdown não pode falhar pela metade.
     *
     * Cada instância é destruída apenas uma vez mesmo que esteja indexada em vários slots
     * (sub-tipo/interface), via Set de identidade.
     */
    private void invokePreDestroyMethods(){
        invokePreDestroyMethods(collectContainerSingletons());
    }

    private List<Object> collectContainerSingletons(){
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> singletons = new ArrayList<>();
        for(Map<String, Dependency> map : dependencyContainer.values()){
            if(map == null) continue;
            for(Dependency dep : map.values()){
                if(dep == null || !dep.isSingleton()) continue;
                Object instance;
                try{
                    instance = dep.getDependency();
                }catch (Exception e){
                    continue;
                }
                if(instance == null) continue;
                if(visited.add(instance)){
                    singletons.add(instance);
                }
            }
        }

        return singletons;
    }

    private void invokePreDestroyMethods(Collection<?> instances){
        if(instances == null || instances.isEmpty()) return;

        Set<Object> destroyed = Collections.newSetFromMap(new IdentityHashMap<>());

        for(Object instance : instances){
            if(instance == null || !destroyed.add(instance)) continue;
            List<Method> destroyMethods = ReflectionCache.methodsWithAnnotation(
                    instance.getClass(), dtm.di.annotations.PreDestroy.class);
            if(destroyMethods.isEmpty()) continue;

            Map<String, Method> methodsBySignature = new LinkedHashMap<>();
            for(Method method : destroyMethods){
                String signature = method.getName() + Arrays.toString(method.getParameterTypes());
                methodsBySignature.putIfAbsent(signature, method);
            }

            List<Method> ordered = new ArrayList<>(methodsBySignature.values());
            ordered.sort(Comparator.<Method>comparingInt(m -> m.getAnnotation(dtm.di.annotations.PreDestroy.class).order()).reversed());

            for(Method method : ordered){
                try{
                    if(!method.canAccess(instance)) method.setAccessible(true);
                    method.invoke(instance);
                }catch (Exception e){
                    log.error("Erro ao executar @PreDestroy {}#{}: {}",
                            instance.getClass().getName(), method.getName(), e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public boolean isLoaded() {
        return loaded.get();
    }

    @Override
    public void loadDirectory(String path) {
        if(isLoaded()) return;

        File folder = new File(path);

        if(folder.exists() && folder.isDirectory()){
            foldersToLoad.add(path);
        }
    }

    @Override
    public void enableChildrenRegistration() {
        this.childrenRegistration = true;
    }

    @Override
    public void disableChildrenRegistration() {
        this.childrenRegistration = false;
    }

    @Override
    public void enableAOP() {
        this.aop = true;
    }

    @Override
    public void disableAOP() {
        this.aop = false;
    }

    @Override
    public boolean isAopEnabled() {
        return aop;
    }

    @Override
    public void setInjectionStrategy(InjectionStrategy injectionStrategy) {
        synchronized (injectionStrategyConfigurationLock){
            this.injectionStrategyConfiguredProgrammatically.set(true);
            this.injectionStrategy.set(injectionStrategy != null ? injectionStrategy : InjectionStrategy.ADAPTIVE);
        }
    }

    private void applyDeclarativeInjectionStrategy() {
        if(injectionStrategyConfiguredProgrammatically.get()) return;

        AppSettings settings = resolveAppSettings();
        if(settings == null){
            settings = new JsonAppSettings(
                    JsonAppSettings.DEFAULT_RESOURCE_NAME,
                    profiles.toArray(String[]::new)
            );
        }

        if(!settings.has(INJECTION_STRATEGY_PROPERTY)) return;

        String configuredStrategy = settings.getString(INJECTION_STRATEGY_PROPERTY, "");
        String normalizedStrategy = configuredStrategy == null
                ? ""
                : configuredStrategy.trim().toUpperCase(Locale.ROOT);
        InjectionStrategy declarativeStrategy;
        boolean invalidStrategy = false;
        try{
            declarativeStrategy = InjectionStrategy.valueOf(normalizedStrategy);
        }catch (IllegalArgumentException e){
            declarativeStrategy = InjectionStrategy.ADAPTIVE;
            invalidStrategy = true;
        }

        synchronized (injectionStrategyConfigurationLock){
            if(injectionStrategyConfiguredProgrammatically.get()) return;
            this.injectionStrategy.set(declarativeStrategy);
            if(invalidStrategy){
                log.warn(
                        "Estratégia de injeção desconhecida '{}' em '{}'. Usando ADAPTIVE.",
                        configuredStrategy,
                        INJECTION_STRATEGY_PROPERTY
                );
            }
        }
    }

    @Override
    public <T> T getDependency(Class<T> reference) {
        throwIfUnload();
        return getDependency(reference, getQualifierName(reference));
    }

    @Override
    public <T> T getDependency(Class<T> reference, String qualifier) {
        throwIfUnload();
        return getDependency(reference, qualifier, () -> true);
    }

    @Override
    public <T> AsyncComponent<T> getDependencyAsync(Class<T> reference, boolean isAsyncComponent) {
        throwIfUnload();
        return getDependencyAsync(reference, getQualifierName(reference), isAsyncComponent);
    }

    @Override
    public <T> AsyncComponent<T> getDependencyAsync(Class<T> reference, String qualifier, boolean isAsyncComponent) {
        if(isAsyncComponent){
            return getAsyncComponent(reference, qualifier, () -> true);
        }
        return new AsyncComponentStorage<>(reference, qualifier, CompletableFuture.supplyAsync(() -> {
            return getDependency(reference, qualifier);
        }, mainExecutor));
    }

    @Override
    public <T> List<T> getDependencyList(Class<T> reference) {
        throwIfUnload();
        return getDependencyListSelf(reference);
    }

    @Override
    public <T, S extends T> Map<Class<S>, S> getInstancesByClass(Class<T> assignableClass) {
        Map<Class<S>, S> classSMap = new ConcurrentHashMap<>();

        for(Map.Entry<Class<?>, Map<String, Dependency>> entry : dependencyContainer.entrySet()){
            final Class<?> refClass = entry.getKey();
            final Map<String, Dependency> dependencyList = entry.getValue();

            if (assignableClass.isAssignableFrom(refClass)) {
                for (Dependency dependency : dependencyList.values()) {
                    try {
                        Object instance = dependency.getDependency();
                        if (instance != null) {
                            classSMap.computeIfAbsent((Class<S>) refClass, k -> (S) instance);
                        }
                    } catch (ClassCastException cce) {
                        log.error("Erro ao fazer cast da instância da classe '{}': {}", refClass.getName(), cce.getMessage(), cce);
                    } catch (Exception e) {
                        log.error("Erro inesperado ao obter instância da classe '{}': {}", refClass.getName(), e.getMessage(), e);
                    }
                }
            }

        }

        return classSMap;
    }

    @Override
    public <T> T newInstance(Class<T> referenceClass) throws NewInstanceException {
        throwIfUnload();
        try{
            T instance = (T)createObject(referenceClass, isAopEnabled(referenceClass));
            registerEventListenersForNewInstance(referenceClass, instance);
            return instance;
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    @Override
    public <T> T newInstance(Class<T> referenceClass, Object... contructorArgs) throws NewInstanceException {
        throwIfUnload();
        try{
            T instance = (T)createObject(referenceClass, isAopEnabled(referenceClass), contructorArgs);
            registerEventListenersForNewInstance(referenceClass, instance);
            return instance;
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    @Override
    public <T> T newInstance(Class<T> referenceClass, Boolean aop, Object... contructorArgs) throws NewInstanceException {
        throwIfUnload();
        try{
            T instance = (T)createObject(referenceClass, ((aop != null)? aop : isAopEnabled(referenceClass)) , contructorArgs);
            registerEventListenersForNewInstance(referenceClass, instance);
            return instance;
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    private void registerEventListenersForNewInstance(Class<?> referenceClass, Object instance) {
        if (referenceClass == null || instance == null) {
            return;
        }

        if (!AnnotationsUtils.hasMetaAnnotation(referenceClass, Event.class)) {
            return;
        }

        try {
            DefaultEventPublisher publisher = getDefaultEventPublisher();

            if (publisher == null) {
                log.warn(
                        "Instancia {} anotada com @Event nao teve @EventListener registrado: DefaultEventPublisher nao encontrado",
                        referenceClass.getName()
                );
                return;
            }

            trackNewInstanceEventListeners(referenceClass, publisher.registerListeners(instance, referenceClass));
        } catch (Exception e) {
            throw new NewInstanceException(
                    "Erro ao registrar @EventListener da instancia " + referenceClass.getName() + ": " + e.getMessage(),
                    referenceClass,
                    e
            );
        }
    }

    private void trackNewInstanceEventListeners(Class<?> referenceClass, EventListenerRegistration listenerRegistration) {
        if (listenerRegistration == null) return;

        ExternalComponentRegistration registration = externalComponentRegistrations.get(referenceClass);

        if (registration == null) return;

        if (!registration.addEventListener(listenerRegistration)) {
            listenerRegistration.unregister();
        }
    }

    private DefaultEventPublisher getDefaultEventPublisher() {
        Map<String, Dependency> publishers = dependencyContainer.get(EventPublisher.class);

        if (publishers == null || publishers.isEmpty()) {
            return null;
        }

        for (Dependency dependency : publishers.values()) {
            if (dependency == null) {
                continue;
            }

            try {
                Object publisher = dependency.getDependency();

                if (publisher instanceof DefaultEventPublisher defaultEventPublisher) {
                    return defaultEventPublisher;
                }
            } catch (Exception e) {
                log.warn("Falha ao obter EventPublisher para registrar listener de newInstance: {}", e.getMessage(), e);
            }
        }

        return null;
    }

    @Override
    public void injectDependencies(Object instance) {
        throwIfUnload();
        injectDependenciesInternal(instance);
    }

    @Override
    public List<Dependency> getRegisteredDependencies() {
        return dependencyContainer.values().stream()
                .flatMap(innerMap -> innerMap.values().stream())
                .toList();
    }

    @Override
    public Set<Class<?>> getLoadedSystemClasses() {
        return loadedSystemClasses;
    }

    @Override
    public boolean hasDependecy(Class<?> referenceClass) {
        if(referenceClass == null) return false;
        return hasDependecy(referenceClass, getQualifierName(referenceClass));
    }

    @Override
    public boolean hasDependecy(Class<?> referenceClass, String qualifier) {
        throwIfUnload();
        if(referenceClass == null) return false;
        if(qualifier == null || qualifier.isEmpty()) return false;
        try{
            final Map<String, Dependency> listOfDependency = getDependencyMap(referenceClass);
            if(listOfDependency.containsKey(qualifier)){
                return true;
            }
            if(AsyncComponent.class.equals(referenceClass)){
                return listOfDependency.values().stream()
                        .anyMatch(dependency -> qualifier.equals(dependency.getQualifier()));
            }
            return false;
        }catch (Exception ignored){
            return false;
        }
    }

    @Override
    public void registerDependency(Object dependency, String qualifier) throws InvalidClassRegistrationException {
        registerObject(dependency, qualifier);
    }

    @Override
    public void registerDependency(Object dependency) throws InvalidClassRegistrationException {
        registerObject(dependency);
    }

    @Override
    public void registerDependency(Object dependency, boolean withAOP) throws InvalidClassRegistrationException {
        registerObject(dependency, withAOP);
    }

    @Override
    public void registerDependency(Object dependency, String qualifier, boolean withAOP) throws InvalidClassRegistrationException {
        registerObject(dependency, qualifier, withAOP);
    }

    @Override
    public <T> void registerDependency(RegistrationFunction<T> registrationFunction) throws InvalidClassRegistrationException {
        registerObjectFunction(registrationFunction, isAopEnabled(registrationFunction.getReferenceClass()));
    }

    @Override
    public <T> void registerDependency(AsyncRegistrationFunction<T> registrationFunction) throws InvalidClassRegistrationException {
        registerObjectFunction(registrationFunction, isAopEnabled(registrationFunction.getReferenceClass()));
    }

    @Override
    public void unRegisterDependency(Class<?> dependency) {
        throwIfUnload();
        if(!dependencyContainer.containsKey(dependency)) return;
        List<Dependency> dependencyList = new ArrayList<>(getDependencyMap(dependency).values());

        for (Dependency dependencyObj : dependencyList){
            for(Class<?> clazz : dependencyObj.getDependencyClassInstanceTypes()){
                dependencyContainer.remove(clazz);
                primaryDependencyIndex.remove(clazz, dependencyObj);
            }
            primaryDependencyIndex.remove(dependencyObj.getDependencyClass(), dependencyObj);
        }
    }

    private void throwIfUnload(){
        if(!isLoaded()) throw new UnloadError("unload: DependencyContainer");
    }

    private Set<Class<?>> normalizeExternalClasses(Collection<Class<?>> classes){
        Objects.requireNonNull(classes, "classes não pode ser null");

        Set<Class<?>> normalized = new LinkedHashSet<>();
        for(Class<?> clazz : classes){
            if(clazz == null){
                throw new IllegalArgumentException("classes não pode conter elementos null");
            }
            normalized.add(clazz);
        }

        return normalized;
    }

    private void loadExternalClasses(Set<Class<?>> classes) throws InvalidClassRegistrationException{
        final Set<Class<?>> candidates = new LinkedHashSet<>();
        for(Class<?> clazz : classes){
            if(!externalComponentRegistrations.containsKey(clazz)){
                candidates.add(clazz);
            }
        }

        if(candidates.isEmpty()) return;

        final Set<Class<?>> componentClasses = filterExternalClasses(candidates, Component.class);
        final Set<Class<?>> configurationClasses = filterExternalClasses(candidates, Configuration.class);

        if(componentClasses.isEmpty() && configurationClasses.isEmpty()) return;

        final Set<Class<?>> knownExternalTypes = new LinkedHashSet<>(componentClasses);
        knownExternalTypes.addAll(externalComponentRegistrations.keySet());

        final ExternalLoadBatch batch = new ExternalLoadBatch(externalRegistrationSequence);

        try{
            final List<Set<ServiceBean>> layers = buildServiceLayers(componentClasses);
            final ConfigurationBeans configurationBeans = resolveConfigurationBeans(configurationClasses, componentClasses);

            registerExternalBeens(configurationBeans.before(), batch, knownExternalTypes);
            loadBeensInlayer(layers, batch, knownExternalTypes);
            registerExternalBeens(configurationBeans.after(), batch, knownExternalTypes);

            for(Class<?> configurationClass : configurationClasses){
                externalRegistrationFor(batch, configurationClass, knownExternalTypes);
            }

            publishExternalBatch(batch);
        }catch (Throwable error){
            rollbackExternalBatch(batch);

            if(error instanceof Error errorToPropagate){
                throw errorToPropagate;
            }

            throw asExternalRegistrationException(error, candidates);
        }
    }

    private void unloadExternalClasses(Set<Class<?>> classes){
        final List<ExternalComponentRegistration> targets = new ArrayList<>();
        final Set<Class<?>> owners = new LinkedHashSet<>();

        for(Class<?> clazz : classes){
            ExternalComponentRegistration registration = externalComponentRegistrations.get(clazz);
            if(registration != null && owners.add(clazz)){
                targets.add(registration);
            }
        }

        if(targets.isEmpty()) return;

        validateExternalDependents(owners);

        List<ExternalComponentRegistration> ordered = externalRegistrationsInReverseOrder(targets);
        destroyExternalRegistrations(ordered);

        for(ExternalComponentRegistration registration : ordered){
            externalComponentRegistrations.remove(registration.getOwnerClass(), registration);
            registration.clear();
        }
    }

    private void validateExternalDependents(Set<Class<?>> owners){
        Map<Class<?>, Set<Class<?>>> dependents = new LinkedHashMap<>();

        for(ExternalComponentRegistration registration : externalComponentRegistrations.values()){
            if(owners.contains(registration.getOwnerClass())) continue;

            for(Class<?> dependency : registration.snapshotDependencies()){
                if(owners.contains(dependency)){
                    dependents.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>())
                            .add(registration.getOwnerClass());
                }
            }
        }

        if(!dependents.isEmpty()){
            throw new ExternalDependencyInUseException(dependents);
        }
    }

    private void destroyExternalRegistrations(List<ExternalComponentRegistration> registrations){
        if(registrations.isEmpty()) return;

        for(ExternalComponentRegistration registration : registrations){
            registration.deactivate();
        }

        cancelAsyncTasks(registrations);
        unregisterEventListeners(registrations);

        List<Object> instances = new ArrayList<>();
        for(ExternalComponentRegistration registration : registrations){
            List<Object> registered = registration.snapshotInstances();
            Collections.reverse(registered);
            instances.addAll(registered);
        }
        invokePreDestroyMethods(instances);

        Set<ClassLoader> loaders = new HashSet<>();
        for(ExternalComponentRegistration registration : registrations){
            List<DependencyRegistrationSlot> slots = registration.snapshotSlots();
            for(int index = slots.size() - 1; index >= 0; index--){
                removeDependencyRegistration(slots.get(index));
            }

            for(Map.Entry<Class<?>, Dependency> primary : registration.snapshotPrimaryTypes().entrySet()){
                primaryDependencyIndex.remove(primary.getKey(), primary.getValue());
            }

            loaders.addAll(registration.classLoaders());
        }

        clearExternalCaches(registrations);
        removeEmptyRegistrations(loaders);
    }

    private void removeDependencyRegistration(DependencyRegistrationSlot slot){
        Map<String, Dependency> registrations = dependencyContainer.get(slot.indexedType());

        if(registrations == null){
            return;
        }

        registrations.remove(slot.qualifier(), slot.dependency());

        if(registrations.isEmpty()){
            dependencyContainer.remove(slot.indexedType(), registrations);
        }

        primaryDependencyIndex.remove(slot.indexedType(), slot.dependency());
    }

    private void removeEmptyRegistrations(Set<ClassLoader> loaders){
        if(loaders.isEmpty()) return;

        final ClassLoader containerLoader = getClass().getClassLoader();

        for(Map.Entry<Class<?>, Map<String, Dependency>> entry : dependencyContainer.entrySet()){
            ClassLoader loader = entry.getKey().getClassLoader();

            if(loader == null || loader == containerLoader || !loaders.contains(loader)) continue;

            Map<String, Dependency> registrations = entry.getValue();
            if(registrations != null && registrations.isEmpty()){
                dependencyContainer.remove(entry.getKey(), registrations);
            }
        }
    }

    private void cancelAsyncTasks(List<ExternalComponentRegistration> registrations){
        for(ExternalComponentRegistration registration : registrations){
            for(CompletableFuture<?> task : registration.snapshotAsyncTasks()){
                try{
                    task.cancel(true);
                }catch (Exception e){
                    log.error("Falha ao cancelar tarefa assíncrona de {}: {}",
                            registration.getOwnerClass().getName(), e.getMessage(), e);
                }
            }
        }
    }

    private void unregisterEventListeners(List<ExternalComponentRegistration> registrations){
        for(ExternalComponentRegistration registration : registrations){
            for(EventListenerRegistration listener : registration.snapshotEventListeners()){
                try{
                    listener.unregister();
                }catch (Exception e){
                    log.error("Falha ao remover listener de {}: {}",
                            registration.getOwnerClass().getName(), e.getMessage(), e);
                }
            }
        }
    }

    private void clearExternalCaches(List<ExternalComponentRegistration> registrations){
        for(ExternalComponentRegistration registration : registrations){
            ProxyFactory.clearCache(registration.snapshotProxyCacheClasses());
            ReflectionCache.clear(registration.snapshotReflectionCacheClasses());
        }
    }

    private List<Object> collectShutdownInstances(List<ExternalComponentRegistration> registrations){
        List<Object> instances = new ArrayList<>();

        for(ExternalComponentRegistration registration : registrations){
            List<Object> registered = registration.snapshotInstances();
            Collections.reverse(registered);
            instances.addAll(registered);
        }

        instances.addAll(collectContainerSingletons());

        return instances;
    }

    private List<ExternalComponentRegistration> externalRegistrationsInReverseOrder(List<ExternalComponentRegistration> registrations){
        List<ExternalComponentRegistration> ordered = new ArrayList<>(registrations);
        ordered.sort(Comparator.comparingLong(ExternalComponentRegistration::getSequence).reversed());
        return ordered;
    }

    private void publishExternalBatch(ExternalLoadBatch batch){
        for(ExternalComponentRegistration registration : batch.inCreationOrder()){
            externalComponentRegistrations.put(registration.getOwnerClass(), registration);
        }
    }

    private void rollbackExternalBatch(ExternalLoadBatch batch){
        List<ExternalComponentRegistration> registrations = batch.inReverseCreationOrder();

        if(registrations.isEmpty()) return;

        try{
            destroyExternalRegistrations(registrations);
        }catch (Exception e){
            log.error("Falha ao desfazer o carregamento externo: {}", e.getMessage(), e);
        }

        for(ExternalComponentRegistration registration : registrations){
            registration.clear();
        }
    }

    private InvalidClassRegistrationException asExternalRegistrationException(Throwable error, Set<Class<?>> candidates){
        Throwable current = error;
        int depth = 0;

        while (current != null && depth++ < 5) {
            if(current instanceof InvalidClassRegistrationException invalidClassRegistrationException){
                return invalidClassRegistrationException;
            }
            current = current.getCause();
        }

        Class<?> reference = candidates.isEmpty() ? null : candidates.iterator().next();

        return new InvalidClassRegistrationException(
                "Erro ao carregar componentes externos ==> causa: " + error.getMessage(),
                reference,
                error
        );
    }

    private Set<Class<?>> filterExternalClasses(Set<Class<?>> candidates, Class<? extends Annotation> annotation){
        Set<Class<?>> filtered = new LinkedHashSet<>();

        for(Class<?> clazz : candidates){
            if(!isConcreteClass(clazz)) continue;
            if(!hasMetaAnnotation(clazz, annotation)) continue;
            if(!isProfileActive(clazz)) continue;
            filtered.add(clazz);
        }

        return filtered;
    }

    private ExternalComponentRegistration externalRegistrationFor(
            ExternalLoadBatch batch,
            Class<?> ownerClass,
            Set<Class<?>> knownExternalTypes
    ){
        if(batch == null) return null;

        ExternalComponentRegistration registration = batch.registrationFor(ownerClass);
        registration.addDependencies(resolveExternalDependencies(ownerClass, knownExternalTypes));

        return registration;
    }

    private Set<Class<?>> resolveExternalDependencies(Class<?> clazz, Set<Class<?>> knownExternalTypes){
        if(knownExternalTypes == null || knownExternalTypes.isEmpty()) return Set.of();

        Set<Class<?>> dependencies = new LinkedHashSet<>();

        for(Class<?> dependency : getDependecyClassListOfClass(clazz, knownExternalTypes)){
            if(knownExternalTypes.contains(dependency)){
                dependencies.add(dependency);
            }
        }

        return dependencies;
    }

    private Set<Class<?>> resolveExternalMethodDependencies(List<Method> methods, Set<Class<?>> knownExternalTypes){
        if(knownExternalTypes == null || knownExternalTypes.isEmpty()) return Set.of();

        Set<Class<?>> dependencies = new LinkedHashSet<>();

        for(Method method : methods){
            for(Parameter parameter : method.getParameters()){
                Class<?> type = parameter.getType();

                if(knownExternalTypes.contains(type)){
                    dependencies.add(type);
                    continue;
                }

                if(type.isInterface() || Modifier.isAbstract(type.getModifiers())){
                    for(Class<?> candidate : knownExternalTypes){
                        if(type.isAssignableFrom(candidate)){
                            dependencies.add(candidate);
                        }
                    }
                }
            }
        }

        return dependencies;
    }

    private void trackExternalSlot(
            ExternalComponentRegistration registration,
            Class<?> indexedType,
            String qualifier,
            Dependency dependency
    ){
        if(registration == null) return;
        registration.addSlot(new DependencyRegistrationSlot(indexedType, qualifier, dependency));
    }

    private void trackExternalType(ExternalComponentRegistration registration, Class<?> clazz){
        if(registration == null || clazz == null) return;

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            registration.addReflectionCacheClass(current);
            current = current.getSuperclass();
        }
    }

    private void trackExternalInstance(
            ExternalComponentRegistration registration,
            Class<?> componentClass,
            Object instance,
            boolean aop
    ){
        if(registration == null) return;

        trackExternalType(registration, componentClass);

        if(aop){
            registration.addProxyCacheClass(componentClass);
        }

        if(instance == null) return;

        registration.addSingletonInstance(instance);
        trackExternalType(registration, instance.getClass());
        registerExternalEventListeners(registration, componentClass, instance);
    }

    private void trackExternalConfigurationInstance(
            ExternalComponentRegistration registration,
            Class<?> configurationClass,
            Object instance
    ){
        if(registration == null || instance == null) return;

        trackExternalType(registration, configurationClass);
        trackExternalType(registration, instance.getClass());
        registration.addSingletonInstance(instance);
    }

    private void trackExternalAsyncTask(
            ExternalComponentRegistration registration,
            Class<?> componentClass,
            boolean aop,
            CompletableFuture<?> task
    ){
        if(registration == null) return;

        registration.addAsyncTask(task);
        trackExternalType(registration, componentClass);

        if(aop){
            registration.addProxyCacheClass(componentClass);
        }

        task.whenComplete((instance, error) -> {
            if(error != null || instance == null || !registration.isActive()) return;

            registration.addSingletonInstance(instance);
            trackExternalType(registration, instance.getClass());
            registerExternalEventListeners(registration, componentClass, instance);
        });
    }

    private void registerExternalEventListeners(
            ExternalComponentRegistration registration,
            Class<?> componentClass,
            Object instance
    ){
        if(registration == null || instance == null) return;
        if(!hasEventListenerMethods(componentClass)) return;

        DefaultEventPublisher publisher = getDefaultEventPublisher();

        if(publisher == null){
            log.warn(
                    "Componente externo {} nao teve seus @EventListener registrados: DefaultEventPublisher nao encontrado",
                    componentClass.getName()
            );
            return;
        }

        EventListenerRegistration listenerRegistration = publisher.registerListeners(instance, componentClass);

        if(!registration.addEventListener(listenerRegistration)){
            listenerRegistration.unregister();
        }
    }

    private boolean hasEventListenerMethods(Class<?> componentClass){
        if(componentClass == null) return false;

        return !ReflectionCache.methodsWithAnnotation(
                componentClass,
                dtm.di.annotations.event.EventListener.class
        ).isEmpty();
    }

    private void loadBeens() throws InvalidClassRegistrationException{
        if(processInlayer){
            loadBeensInlayer();
        }else{
            loadBeensTopological();
        }
    }

    private void loadBeensInlayer() throws InvalidClassRegistrationException{
        for (Set<ServiceBean> layer : serviceBeensDefinitionLayer) {
            loadBeensInlayer(layer, null, null);
        }
    }

    private void loadBeensInlayer(
            List<Set<ServiceBean>> layers,
            ExternalLoadBatch batch,
            Set<Class<?>> knownExternalTypes
    ) throws InvalidClassRegistrationException{
        for (Set<ServiceBean> layer : layers) {
            loadBeensInlayer(layer, batch, knownExternalTypes);
        }
    }

    private void loadBeensInlayer(
            Set<ServiceBean> layer,
            ExternalLoadBatch batch,
            Set<Class<?>> knownExternalTypes
    ) throws InvalidClassRegistrationException{
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        for (ServiceBean serviceBean : layer) {
            final ExternalComponentRegistration registration = externalRegistrationFor(
                    batch,
                    serviceBean.getClazz(),
                    knownExternalTypes
            );
            CompletableFuture<?> task = CompletableFuture.runAsync(() -> {
                try {
                    loadBeen(serviceBean, new HashSet<>(), getQualifierName(serviceBean.getClazz()), registration);
                } catch (InvalidClassRegistrationException e) {
                    throw new RuntimeException(e);
                }
            }, mainExecutor);
            tasks.add(task);
        }

        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get();
        }catch (Exception e){
            if (e instanceof InvalidClassRegistrationException invalidClassRegistrationException) {
                throw invalidClassRegistrationException;
            } else {
                if(e instanceof RuntimeException runtimeException){
                    Throwable cause = runtimeException.getCause();
                    if (cause instanceof InvalidClassRegistrationException invalidClassRegistrationException) {
                        throw invalidClassRegistrationException;
                    }

                    throw new DependencyInjectionException((cause != null) ? cause : runtimeException);
                }

                throw new DependencyInjectionException(e);
            }

        }
    }

    private void loadBeensTopological() throws InvalidClassRegistrationException{
        for (ServiceBean service: serviceBeensDefinition){
            loadBeen(service, new HashSet<>(), getQualifierName(service.getClazz()), null);
        }
    }

    private void loadBeen(
            ServiceBean been,
            final Set<Class<?>> registeringClasses,
            String qualifier,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException{
        if(!isProfileActive(been.getClazz())) return;
        if(hasMetaAnnotation(been.getClazz(), Async.class)){
            loadAsyncBeen(been, registeringClasses, qualifier, registration);
        }else{
            loadDefaultBeen(been, registeringClasses, qualifier, registration);
        }
    }

    private void loadDefaultBeen(
            ServiceBean been,
            final Set<Class<?>> registeringClasses,
            String qualifier,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException{
        final Class<?> dependency = been.getClazz();

        try {
            if (dependencyContainer.containsKey(dependency)) return;
            validRegistration(dependency, registeringClasses);
            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(dependency, qualifier, childrenRegistration);

            boolean singleton = isSingleton(dependency);
            Object singletonInstance = singleton ? createObject(dependency, been.isAop()) : null;

            DependencyObject dependencyObject = singleton
                   ? DependencyObject.builder()
                            .dependencyClass(dependency)
                            .qualifier(qualifier)
                            .singleton(true)
                            .creatorFunction(null)
                            .singletonInstance(singletonInstance)
                        .build()
                   : DependencyObject.builder()
                            .dependencyClass(dependency)
                            .qualifier(qualifier)
                            .singleton(false)
                            .creatorFunction(createActivationFunction(dependency, been.isAop()))
                            .singletonInstance(null)
                        .build();


            registerInContainer(
                    mapOfDependency,
                    dependency,
                    dependencyObject,
                    qualifier,
                    registration
            );

            trackExternalInstance(registration, dependency, singletonInstance, been.isAop());
        }catch (Exception e) {
            log.error("Falha ao registrar a dependência: {}", dependency.getName(), e);
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + dependency+ " ==> causa: "+e.getMessage(),
                    dependency,
                    e
            );
        }
    }

    private void loadAsyncBeen(
            ServiceBean been,
            final Set<Class<?>> registeringClasses,
            String qualifier,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException{
        final Class<?> dependency = been.getClazz();

        try {
            if (dependencyContainer.containsKey(dependency)) return;
            validRegistration(dependency, registeringClasses);

            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(AsyncComponent.class, qualifier, dependency);
            if(mapOfDependency.values().stream().anyMatch(d -> d.getDependencyClass().equals(dependency))){
                return;
            }

            CompletableFuture<?> resolveComponentAsync = CompletableFuture.supplyAsync(() -> {
                boolean shouldApplyAop = been.isAop();
                Object instance = createObject(dependency, been.isAop());

                if (instance == null) {
                    throw new InvalidClassRegistrationException("Instância inválida para " + dependency, dependency);
                }

                return shouldApplyAop ? proxyObject(instance, instance.getClass()) : instance;
            }, mainExecutor);

            trackExternalAsyncTask(registration, dependency, been.isAop(), resolveComponentAsync);

            Supplier<?> activatorFunction = () -> new AsyncComponentStorage<>(dependency, qualifier, resolveComponentAsync);

            DependencyObject dependencyObject = new DependencyObject(dependency, qualifier, false, activatorFunction, activatorFunction);

            registerInContainer(
                    mapOfDependency,
                    dependency,
                    dependencyObject,
                    qualifier,
                    registration
            );
        }catch (Exception e) {
            log.error("Falha ao registrar a dependência: {}", dependency.getName(), e);
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + dependency+ " ==> causa: "+e.getMessage(),
                    dependency,
                    e
            );
        }
    }



    private void registerAutoInject(@NonNull Class<?> clazz, final Set<Class<?>> registeringClasses) throws InvalidClassRegistrationException{
        List<Class<?>> listOfRegistration = ReflectionCache.fields(clazz).stream()
                .filter(f -> {
                    Class<?> fieldClass = f.getType();
                    return f.isAnnotationPresent(Inject.class) && !(
                            fieldClass.isInterface() ||
                                    fieldClass.isEnum() ||
                                    fieldClass.isAnnotation() ||
                                    Modifier.isAbstract(fieldClass.getModifiers())
                    );
                })
                .map(Field::getType)
                .collect(Collectors.toList());

        int order = 0;
        for(Class<?> subClass : listOfRegistration){
            if(!dependencyContainer.containsKey(subClass) && isProfileActive(subClass)){
                loadBeen(new ServiceBean(subClass, order++, isAopEnabled(clazz)), registeringClasses, getQualifierName(subClass), null);
            }
        }
    }

    private void validRegistration(@NonNull Class<?> dependency, final Set<Class<?>> registeringClasses) throws InvalidClassRegistrationException{
        if(dependency.isEnum() || dependency.isInterface() || Modifier.isAbstract(dependency.getModifiers())){
            throw new InvalidClassRegistrationException("Registre uma classe concreta para: "+dependency, dependency);
        }
        if (registeringClasses.contains(dependency)) {
            throw new InvalidClassRegistrationException("Dependência circular detectada: " + dependency.getName(), dependency);
        }
        registeringClasses.add(dependency);
    }

    private void validQualifier(final Map<String, Dependency> listOfDependency, String qualifier, Class<?> dependency) throws InvalidClassRegistrationException{
        final boolean containsQualifier = listOfDependency.containsKey(qualifier);
        if(containsQualifier){
            throw new InvalidClassRegistrationException("Qualificador '"+qualifier+"' ja registrado para a dependencia: "+dependency, dependency);
        }
    }

    private void filterServiceClass(){
        final Set<Class<?>> serviceLoadedClassActive = getConcreteServiceLoadedClass(Component.class);
        serviceLoadedClassActive.addAll(getConcreteServiceLoadedClass(Aspect.class));

        final Map<Class<?>, Set<Class<?>>> dependencyGraph = buildDependencyGraph(serviceLoadedClassActive);

        if(processInlayer){
            serviceBeensDefinitionLayer.addAll(buildServiceLayers(serviceLoadedClassActive, dependencyGraph));
        }else{
            Set<Class<?>> ordered = TopologicalSorter.sort(serviceLoadedClassActive, dependencyGraph);
            int order = 0;
            for (Class<?> clazz : ordered) {
                serviceBeensDefinition.add(new ServiceBean(clazz, order++, isAopEnabled(clazz)));
            }
        }
    }

    private Map<Class<?>, Set<Class<?>>> buildDependencyGraph(Set<Class<?>> serviceClasses){
        final Map<Class<?>, Set<Class<?>>> dependencyGraph = new ConcurrentHashMap<>();

        if(serviceClasses.isEmpty()) return dependencyGraph;

        if (serviceClasses.size() < thresholdConcurent) {
            processDependencyServiceWithParallelStream(dependencyGraph, serviceClasses);
        } else {
            processDependencyServiceWithExecutorService(dependencyGraph, serviceClasses);
        }

        return dependencyGraph;
    }

    private List<Set<ServiceBean>> buildServiceLayers(Set<Class<?>> serviceClasses){
        return buildServiceLayers(serviceClasses, buildDependencyGraph(serviceClasses));
    }

    private List<Set<ServiceBean>> buildServiceLayers(Set<Class<?>> serviceClasses, Map<Class<?>, Set<Class<?>>> dependencyGraph){
        List<Set<ServiceBean>> layers = new ArrayList<>();

        if(serviceClasses.isEmpty()) return layers;

        List<Set<Class<?>>> classLayers = groupByDependencyLayer(serviceClasses, dependencyGraph);

        int order = 0;
        for (Set<Class<?>> classSet : classLayers) {
            int layerOrder = order;
            Set<ServiceBean> layer = ConcurrentHashMap.newKeySet();

            classSet.parallelStream().forEach(clazz -> {
                layer.add(new ServiceBean(clazz, layerOrder, isAopEnabled(clazz)));
            });

            layers.add(layer);
            order++;
        }

        return layers;
    }

    private void loadByPluginFolder(){
        for (String forderPath : foldersToLoad){
            classFinder.loadByDirectory(forderPath);
        }
    }

    private ClassFinderConfigurations getFindConfigurations(){
        return new ClassFinderConfigurationsStorage();
    }

    private String getQualifierName(@NonNull Class<?> clazz){
        if(clazz.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = clazz.getAnnotation(Qualifier.class);
            return (qualifierAnnotation.value() == null || qualifierAnnotation.value().isEmpty()) ? "default" : qualifierAnnotation.value();
        } else if(clazz.isAnnotationPresent(dtm.di.annotations.Primary.class)){
            return "$primary$:" + clazz.getName();
        } else {
            return  "default";
        }
    }

    private String getQualifierName(@NonNull Field variable){
        if(variable.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = variable.getAnnotation(Qualifier.class);
            return (qualifierAnnotation.value() == null || qualifierAnnotation.value().isEmpty()) ? "default" : qualifierAnnotation.value();
        } else if(variable.isAnnotationPresent(Inject.class)) {
            Inject inject = variable.getAnnotation(Inject.class);
            return (inject.qualifier() == null || inject.qualifier().isEmpty()) ? "default" : inject.qualifier();
        }else {
            return  "default";
        }
    }

    private String getQualifierName(@NonNull Parameter variable){
        if(variable.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = variable.getAnnotation(Qualifier.class);
            return (qualifierAnnotation.value() == null || qualifierAnnotation.value().isEmpty()) ? "default" : qualifierAnnotation.value();
        } else {
            return  "default";
        }
    }

    private String getQualifierName(@NonNull AnnotatedElement variable){
        if(variable.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = variable.getAnnotation(Qualifier.class);
            return (qualifierAnnotation.value() == null || qualifierAnnotation.value().isEmpty()) ? "default" : qualifierAnnotation.value();
        } else if(variable.isAnnotationPresent(Inject.class)) {
            Inject inject = variable.getAnnotation(Inject.class);
            return (inject.qualifier() == null || inject.qualifier().isEmpty()) ? "default" : inject.qualifier();
        }else {
            return  "default";
        }
    }

    private String getQualifierName(@NonNull Method beenMethod){
        String resolved = null;
        if(beenMethod.isAnnotationPresent(Service.class)){
            Service qualifierAnnotation = beenMethod.getAnnotation(Service.class);
            resolved = (qualifierAnnotation.qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? null : qualifierAnnotation.qualifier();
        }else if(beenMethod.isAnnotationPresent(Component.class)){
            Component qualifierAnnotation = beenMethod.getAnnotation(Component.class);
            resolved = (qualifierAnnotation.qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? null : qualifierAnnotation.qualifier();
        } else if(beenMethod.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = beenMethod.getAnnotation(Qualifier.class);
            resolved = (qualifierAnnotation.value() == null || qualifierAnnotation.value().isEmpty()) ? null : qualifierAnnotation.value();
        }
        if(resolved != null) return resolved;
        if(beenMethod.isAnnotationPresent(Primary.class)){
            return "$primary$:" + beenMethod.getDeclaringClass().getName() + "#" + beenMethod.getName();
        }
        return  "default";
    }

    private boolean isSingletonBeen(@NonNull Method method){
        if(method.isAnnotationPresent(BeanDefinition.class)){
            return method.getAnnotation(BeanDefinition.class).proxyType() == BeanDefinition.ProxyType.STATIC;
        }
        return true;
    }

    private boolean isSingleton(@NonNull Class<?> clazz){
        return clazz.isAnnotationPresent(Singleton.class);
    }

    private Set<Class<?>> getConcreteServiceLoadedClass(Class<? extends Annotation> annotation){
        return getConcreteServiceLoadedClass(annotation, true);
    }

    private Set<Class<?>> getConcreteServiceLoadedClass(Class<? extends Annotation> annotation, boolean onlyActive){
        final int threshold = 350;
        final int total = loadedSystemClasses.size();

        Predicate<Class<?>> filterConcrete = c -> (onlyActive) ? filterConcreteBeanAndActive(c, annotation) : filterConcreteBean(c, annotation);

        if (total < threshold) {
            return loadedSystemClasses.stream()
                    .parallel()
                    .filter(filterConcrete)
                    .collect(Collectors.toSet());
        }else{
            final List<CompletableFuture<?>> futures = new ArrayList<>();
            final Set<Class<?>> result = ConcurrentHashMap.newKeySet();
            try{
                List<Class<?>> classList = new ArrayList<>(loadedSystemClasses);

                for(Class<?> clazz : classList){
                    futures.add(CompletableFuture.runAsync(() -> {
                        if(filterConcrete.test(clazz)){
                            result.add(clazz);
                        }
                    }, mainVirtualExecutor));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                throw new DependencyContainerException("erro ao caregar dependencias", e.getCause());
            }

            return result;
        }
    }

    private void processDependencyServiceWithParallelStream(Map<Class<?>, Set<Class<?>>> dependencyGraph, Set<Class<?>> serviceLoadedClass) {
        serviceLoadedClass.parallelStream()
                .forEach(clazz -> {
                    Set<Class<?>> dependencies = getDependecyClassListOfClass(clazz, serviceLoadedClass);
                    dependencyGraph.put(clazz, dependencies);
                });
    }

    private Set<Class<?>> getDependecyClassListOfClass(Class<?> clazz, Set<Class<?>> serviceLoadedClass) {
        Set<Class<?>> dependencies = new HashSet<>();

        for (Field field : ReflectionCache.fields(clazz)) {
            if (field.isAnnotationPresent(Inject.class)) {
                Class<?> fieldType = field.getType();
                dependencies.addAll(isServiceDependency(fieldType, serviceLoadedClass, field));
            }
        }

        for (Constructor<?> constructor : ReflectionCache.constructors(clazz)) {
            for (Parameter param : constructor.getParameters()) {
                if (param.isAnnotationPresent(Value.class)) {
                    continue;
                }
                dependencies.addAll(isServiceDependency(param.getType(), serviceLoadedClass, param));
            }
        }

        return dependencies;
    }

    private Set<Class<?>> isServiceDependency(Class<?> type, Set<Class<?>> serviceLoadedClass, Object extra) {
        Set<Class<?>> dependencies = new HashSet<>();

        if(type.isInterface() || Modifier.isAbstract(type.getModifiers())){
            for (Class<?> serviceClass : serviceLoadedClass) {
                if (type.isAssignableFrom(serviceClass) && !serviceClass.isInterface() && !Modifier.isAbstract(serviceClass.getModifiers())) {
                    String serviceQualifier = getQualifierName(serviceClass);
                    String qualifierElement = "default";
                    if(extra instanceof Field field){
                        qualifierElement = getQualifierName(field);
                    }else if(extra instanceof Parameter parameter){
                        qualifierElement = getQualifierName(parameter);
                    }
                    if (serviceQualifier.equalsIgnoreCase(qualifierElement)){
                        dependencies.add(serviceClass);
                    }

                }
            }
        }else{
            dependencies.add(type);
        }

        return dependencies;
    }

    private void processDependencyServiceWithExecutorService(Map<Class<?>, Set<Class<?>>> dependencyGraph, Set<Class<?>> serviceLoadedClass) {
        try{
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for(Class<?> serviceClass : serviceLoadedClass){
                futures.add(CompletableFuture.runAsync(() -> {
                    if(!serviceClass.isInterface() && !Modifier.isAbstract(serviceClass.getModifiers())){
                        Set<Class<?>> dependencies = getDependecyClassListOfClass(serviceClass, serviceLoadedClass);
                        dependencyGraph.put(serviceClass, dependencies);
                    }
                }, mainExecutor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();
            } catch (ExecutionException e) {
                throw new DependencyContainerRuntimeException("Erro ao processar uma classe", e.getCause());
            }
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new DependencyContainerRuntimeException("Execução interrompida", e);
        }

    }

    private void filterExternalsBeens() throws InvalidClassRegistrationException{
        Set<Class<?>> configClasses = getConcreteServiceLoadedClass(Configuration.class);

        if (configClasses.isEmpty()) {
            return;
        }

        Set<Class<?>> serviceClasses = getConcreteServiceLoadedClass(Component.class, false);
        ConfigurationBeans configurationBeans = resolveConfigurationBeans(configClasses, serviceClasses);

        this.externalBeenBefore.clear();
        this.externalBeenBefore.putAll(configurationBeans.before());

        this.externalBeenAfter.clear();
        this.externalBeenAfter.putAll(configurationBeans.after());
    }

    private ConfigurationBeans resolveConfigurationBeans(Set<Class<?>> configurationClasses, Set<Class<?>> serviceClasses){
        if(configurationClasses.isEmpty()) return ConfigurationBeans.empty();

        BeanGraph beanGraph = new BeanDependencyGraphBuilder(serviceClasses, this::isProfileActive)
                .buildGraph(configurationClasses);

        return new ConfigurationBeans(
                beanGraph.getBeforeServiceBeans(serviceClasses),
                beanGraph.getAfterServiceBeans(serviceClasses)
        );
    }

    private record ConfigurationBeans(Map<Class<?>, List<Method>> before, Map<Class<?>, List<Method>> after){
        private static ConfigurationBeans empty(){
            return new ConfigurationBeans(Map.of(), Map.of());
        }
    }



    private void selfInjection() throws InvalidClassRegistrationException{
        registerObject(this);
    }

    private void registerExternalBeens(
            Map<Class<?>, List<Method>> configurationsClasses,
            ExternalLoadBatch batch,
            Set<Class<?>> knownExternalTypes
    ) throws InvalidClassRegistrationException{
        for(Map.Entry<Class<?>, List<Method>> configurationsClass : configurationsClasses.entrySet()){
            final Class<?> clazz = configurationsClass.getKey();
            List<Method> methodsList = configurationsClass.getValue();

            if(!methodsList.isEmpty()){
                ExternalComponentRegistration registration = externalRegistrationFor(batch, clazz, knownExternalTypes);
                if(registration != null){
                    registration.addDependencies(resolveExternalMethodDependencies(methodsList, knownExternalTypes));
                }
                registerExternalBeen(clazz, methodsList, true, registration);
            }
        }
    }


    private void registerExternalBeen(
            Class<?> configurationsClass,
            List<Method> methodsList,
            boolean load,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException{
        try {
            Object configurationInstance = newInstance(configurationsClass, false);
            trackExternalConfigurationInstance(registration, configurationsClass, configurationInstance);
            for (Method method : methodsList) {
                Parameter[] parameters = method.getParameters();
                Object[] args = new Object[parameters.length];

                if(load){
                    for(int i = 0; i < parameters.length; i++){
                        final Parameter parameter = parameters[i];
                        validateAsyncProducerDependency(parameter, method);
                        try{
                            args[i] = getDependecyObjectByParam(parameter, configurationInstance, method.isAnnotationPresent(DisableInjectionWarn.class));
                        }catch (Exception e){
                            log.error("Erro ao abter parametro: {} no metodo: {}, classe: {}", parameter.getName(), method.getName(), configurationsClass);
                            args[i] = null;
                        }
                    }
                }else{
                    Arrays.fill(args, null);
                }

                if(!method.canAccess(configurationInstance)){
                    method.setAccessible(true);
                }

                if(method.isAnnotationPresent(Async.class)){
                    registerAsyncProducer(configurationInstance, method, args, registration);
                    continue;
                }

                ThrowableAction action = () -> {
                    Object result = method.invoke(configurationInstance, args);

                    if(result != null){
                        String qualifier = getQualifierName(method);
                        boolean singleton = isSingletonBeen(method);
                        if(singleton){

                            if(result instanceof AsyncRegistrationFunction<?> asyncRegistrationFunction){
                                registerObjectFunction(asyncRegistrationFunction, isAopEnabled(method), registration);
                            }else if(result instanceof RegistrationFunction<?> registrationFunction){
                                registerObjectFunction(registrationFunction, isAopEnabled(method), registration);
                            }else{
                                boolean aop = (isAopEnabled(method) && isAopEnabled(result.getClass()));
                                registerObject(result, qualifier, aop, registration);
                            }

                        }else {
                            registerExternalBeenNoSinglenton(result, method, qualifier, registration);
                        }
                    }
                };

                action.run();
            }
        } catch (Throwable e) {
            throw new InvalidClassRegistrationException("Erro ao configurar: "+configurationsClass, configurationsClass, e);
        }
    }

    private void registerAsyncProducer(
            Object configurationInstance,
            Method method,
            Object[] args,
            ExternalComponentRegistration registration
    ){
        Class<?> referenceClass = method.getReturnType();
        if(referenceClass.equals(Void.TYPE)
                || RegistrationFunction.class.isAssignableFrom(referenceClass)){
            throw new InvalidClassRegistrationException(
                    "Produtor @Async deve retornar diretamente o tipo do bean: " + method,
                    method.getDeclaringClass()
            );
        }
        if(method.isAnnotationPresent(BeanDefinition.class)
                && !isSingletonBeen(method)){
            throw new InvalidClassRegistrationException(
                    "Produtor @Async não suporta @BeanDefinition(INSTANCE): " + method,
                    method.getDeclaringClass()
            );
        }
        if(method.isAnnotationPresent(Primary.class)){
            throw new InvalidClassRegistrationException(
                    "Produtor @Async não suporta @Primary; selecione AsyncComponent<T> por tipo e qualifier: " + method,
                    method.getDeclaringClass()
            );
        }

        String qualifier = getQualifierName(method);
        AsyncRegistrationFunction<Object> asyncProducer = new AsyncRegistrationFunction<>() {
            @Override
            public ExecutorService getExecutor() {
                return mainExecutor;
            }

            @Override
            public Supplier<Object> getFunction() {
                return () -> {
                    try{
                        Object result = method.invoke(configurationInstance, args);
                        if(result == null){
                            throw new InvalidClassRegistrationException(
                                    "Produtor @Async retornou null: " + method,
                                    referenceClass
                            );
                        }

                        return result;
                    }catch (InvocationTargetException e){
                        Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                        throw new CompletionException(cause);
                    }catch (Exception e){
                        throw new CompletionException(e);
                    }
                };
            }

            @Override
            public Class<Object> getReferenceClass() {
                return (Class<Object>) referenceClass;
            }

            @Override
            public String getQualifier() {
                return qualifier;
            }
        };

        registerObjectFunction(asyncProducer, isAopEnabled(method), registration);
    }

    private void validateAsyncProducerDependency(Parameter parameter, Method consumer){
        if(AsyncComponent.class.equals(parameter.getType())){
            return;
        }

        String qualifier = getQualifierName(parameter);
        Map<String, Dependency> synchronous = dependencyContainer.get(parameter.getType());
        boolean hasSynchronousDependency = resolveWithPrimary(parameter.getType(), synchronous, qualifier) != null;
        if(!hasSynchronousDependency && hasAsyncComponentRegistration(parameter.getType(), qualifier)){
            throw new InvalidClassRegistrationException(
                    "O produtor '" + consumer.getName() + "' depende diretamente de "
                            + parameter.getType().getName() + ", criado por um produtor @Async. "
                            + "Receba AsyncComponent<" + parameter.getType().getSimpleName() + ">.",
                    consumer.getDeclaringClass()
            );
        }
    }

    private boolean hasAsyncComponentRegistration(Class<?> referenceClass, String qualifier){
        Map<String, Dependency> registrations = dependencyContainer.get(AsyncComponent.class);
        if(registrations == null || registrations.isEmpty()){
            return false;
        }

        return registrations.values().stream().anyMatch(dependency ->
                referenceClass.equals(dependency.getDependencyClass())
                        && qualifier.equals(dependency.getQualifier())
        );
    }

    private Object createObject(@NonNull Class<?> clazz){
        return createObject(clazz, isAopEnabled(clazz));
    }

    private Object createObject(@NonNull Class<?> clazz, boolean aop){
        try {
            Object instance = null;
            Constructor<?>[] constructors = ReflectionCache.constructors(clazz).toArray(new Constructor<?>[0]);
            for (Constructor<?> constructor : constructors) {
                if (constructor.getParameterCount() == 0) {
                    instance = createWithOutConstructor(clazz);
                    break;
                }
            }
            instance = (instance == null) ? createWithConstructor(clazz, constructors) : instance;
            injectDependenciesInternal(Objects.requireNonNull(instance));
            Object object =  (aop) ? proxyObject(instance, clazz) : instance;
            executePostCreationMethod(clazz, object);
            return object;
        }catch (Exception e) {
            log.error("Erro ao criar instância para a classe: {}", clazz.getName(), e);
            String message = "Erro ao criar instância "+clazz+" ==> cause: "+e.getMessage();
            if(e instanceof NewInstanceException instanceException){
                throw instanceException;
            }
            throw new NewInstanceException(message, clazz);
        }
    }

    private Object createObject(@NonNull Class<?> clazz, boolean aop, Object[] extraConstructorArgs){
        try {
            Constructor<?>[] constructors = ReflectionCache.constructors(clazz).toArray(new Constructor<?>[0]);
            List<Parameter> failedParams = new ArrayList<>();
            for (Constructor<?> constructor : constructors) {
                Parameter[] parameterTypes = constructor.getParameters();
                Object[] resolvedArgs = tryResolveConstructorArgs(parameterTypes, extraConstructorArgs, failedParams, clazz);

                if (resolvedArgs != null) {
                    constructor.setAccessible(true);
                    Object instance = constructor.newInstance(resolvedArgs);
                    injectDependenciesInternal(instance);
                    Object object =  (aop) ? proxyObject(instance, clazz) : instance;
                    executePostCreationMethod(clazz, object);
                    return object;
                }
            }

            String message;
            if (!failedParams.isEmpty()) {
                StringBuilder errorMsg = new StringBuilder("Falha ao instanciar " + clazz.getName() + ". Parâmetros não resolvidos:\n");
                for (Parameter p : failedParams) {
                    errorMsg.append("- ").append(p.getName()).append(" : ").append(p.getType().getName()).append("\n");
                }
                message = errorMsg.toString();
            }else{
                message = "Sem construtor aplicável encontrado para " + clazz.getName();
            }
            throw new NewInstanceException(message, clazz);
        }catch (Exception e) {
            String message = "Erro ao criar Objeto "+clazz+" ==> cause: "+e.getMessage();
            throw new NewInstanceException(message, clazz, e);
        }
    }

    private Supplier<Object> createActivationFunction(@NonNull Class<?> clazz){
        return () -> {
            return createObject(clazz, aop);
        };
    }

    private Supplier<Object> createActivationFunction(@NonNull Class<?> clazz, boolean aop){
        return () -> {
            return createObject(clazz, aop);
        };
    }


    private Object createWithOutConstructor(@NonNull Class<?> clazz) throws Exception{
        return clazz.getDeclaredConstructor().newInstance();
    }

    private Object createWithConstructor(@NonNull Class<?> clazz, @NonNull Constructor<?>[] constructors){
        try{
            Constructor<?> chosenConstructor = getSelectedConstructor(constructors, clazz);
            Parameter[] parameters = chosenConstructor.getParameters();
            Object[] args = Arrays.stream(parameters)
                    .map(e -> (getDependecyObjectByParam(e, clazz)))
                    .toArray();

            return chosenConstructor.newInstance(args);
        }catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            throw new NewInstanceException(
                    "Constructor of " + clazz.getName() + " threw an exception: " + cause.getMessage(),
                    clazz,
                    cause
            );
        } catch (Exception e){
            log.error("Falha ao criar instância de {} com construtor. Tentando fallback sem construtor. Erro: {}", clazz.getName(), e.getMessage(), e);
            try{
                return createWithOutConstructor(clazz);
            }catch (Exception ex){
                log.error("Falha ao criar instância de {} até mesmo via fallback. Erro: {}", clazz.getName(), ex.getMessage(), ex);
                throw new NewInstanceException(
                        "Failed to create instance of " + clazz.getName() + " even using fallback constructor.",
                        clazz,
                        ex
                );
            }
        }
    }

    private Object getDependecyObjectByParam(Parameter parameter, Object instance){
        return getDependecyObjectByParam(parameter, instance, false);
    }

    private Object getDependecyObjectByParam(Parameter parameter, Object instance, boolean desableAllWarn){
        if(parameter.isAnnotationPresent(Value.class)){
            return resolveValueAnnotation(parameter);
        }

        final ParamtrizedObject paramtrizedObject = extractType(parameter);
        boolean disableWarn = desableAllWarn || isInjectionWarnDisabled(parameter, instance);

        if(paramtrizedObject.isParametrized()){
            return getParamObject(paramtrizedObject.getBaseClass(), paramtrizedObject.getParamType(), parameter, false, instance, disableWarn);
        }else{
            return getDependency(paramtrizedObject.getBaseClass(), () -> {
                return !disableWarn;
            }, describeInjectionOrigin(parameter, instance));
        }
    }

    private Object getDependencyObjectByField(Field variable, Object instance){
        final ParamtrizedObject paramtrizedObject = extractType(variable);
        boolean disableWarn = isInjectionWarnDisabled(variable, instance);

        if(paramtrizedObject.isParametrized()){
            return getParamObject(paramtrizedObject.getBaseClass(), paramtrizedObject.getParamType(), variable, true, instance, disableWarn);
        }else{
            return getDependency(paramtrizedObject.getBaseClass(), () -> !disableWarn, describeInjectionOrigin(variable, instance));
        }
    }

    private Object getParamObject(
            final Class<?> rawType,
            final Type genericType,
            AnnotatedElement element,
            boolean useElementToGetQualifier,
            Object instance,
            boolean disableWarn
    ) {
        String qualifier = useElementToGetQualifier ? getQualifierName(element) : getQualifierName(rawType);
        boolean warn = !disableWarn;

        if (LazyDependency.class.equals(rawType)) {
            return Lazy.of(() -> resolveNestedObject(genericType, element, qualifier, instance, warn));
        }

        if (AsyncComponent.class.equals(rawType)) {
            Type innerType = (genericType instanceof ParameterizedType pt)
                    ? pt.getActualTypeArguments()[0]
                    : genericType;

            validateTerminalType(rawType, innerType, instance);
            return wrapInContainer(rawType, null, extractRawClass(innerType), qualifier, warn);
        }

        Object innerObject = resolveNestedObject(genericType, element, qualifier, instance, warn);
        return wrapInContainer(rawType, innerObject, extractRawClass(genericType), qualifier, warn);
    }

    private Object resolveNestedObject(Type type, AnnotatedElement element, String qualifier, Object instance, boolean warn) {
        if (!(type instanceof ParameterizedType paramType)) {
            return getDependency((Class<?>) type, qualifier, () -> warn, describeInjectionOrigin(element, instance));
        }

        Class<?> nextRaw = (Class<?>) paramType.getRawType();
        Type innerType = paramType.getActualTypeArguments()[0];

        if (AsyncComponent.class.equals(nextRaw)) {
            validateTerminalType(nextRaw, innerType, instance);
            return wrapInContainer(nextRaw, null, (Class<?>) innerType, qualifier, warn);
        }

        return getParamObject(nextRaw, innerType, element, false, instance, !warn);
    }

    private void validateTerminalType(Class<?> nextRaw, Type innerType, Object instance) {
        if (innerType instanceof ParameterizedType) {
            String whereError = (instance instanceof String s) ? s :
                    (instance != null ? instance.getClass().getName() : "unknown");

            throw new DependencyInjectionException(
                    String.format("O tipo '%s' deve ser terminal. Não é permitido aninhamento dentro de AsyncComponent (Encontrado: %s) em: %s",
                            nextRaw.getSimpleName(), innerType.getTypeName(), whereError)
            );
        }
    }

    private Class<?> extractRawClass(Type type) {
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        return (Class<?>) type;
    }

    private Object wrapInContainer(
            Class<?> containerType,
            Object resolvedInner,
            Class<?> targetClass,
            String qualifier,
            boolean warn
    ) {
        if (containerType.equals(LazyDependency.class)) {
            return Lazy.of(() -> resolvedInner != null ? resolvedInner : getDependency(targetClass, qualifier, () -> warn));
        }

        if (containerType.equals(AsyncComponent.class)) {
            return (resolvedInner instanceof AsyncComponent<?>) ? resolvedInner : getAsyncComponent(targetClass, qualifier, () -> warn);
        }

        if (containerType.equals(CompositeDependency.class)) {
            List<?> list = getDependencyListSelf(targetClass);
            return new CompositeDependencyStorage<>((list != null) ? list : List.of());
        }

        if (containerType.equals(AtomicReference.class)) return new AtomicReference<>(resolvedInner);
        if (containerType.equals(WeakReference.class)) return new WeakReference<>(resolvedInner);
        if (containerType.equals(SoftReference.class)) return new SoftReference<>(resolvedInner);

        return resolvedInner;
    }

    private <T> AsyncComponent<T> getAsyncComponent(final Class<T> reference, final String qualifier, Supplier<Boolean> showWarnIfError){
        try{
            final Map<String, Dependency> listOfDependency = getDependencyMap(AsyncComponent.class);
            final Dependency dependencyObject = listOfDependency
                    .values()
                    .stream()
                    .filter(d -> d.getQualifier().equals(qualifier))
                    .filter(d -> reference.equals(d.getDependencyClass()))
                    .findFirst()
                    .orElseThrow(() -> {
                return new DependencyInjectionException("Erro ao obter dependência: reference="+reference+", qualifier="+qualifier);
            });
            Object asyncComponentObject = dependencyObject.getDependency();
            if(asyncComponentObject instanceof AsyncComponent<?> asyncComponent){
                return asyncComponent.getReferenceClass().equals(reference) ? (AsyncComponent<T>) asyncComponent : null;
            }

            if(showWarnIfError == null) showWarnIfError = () -> true;

            Boolean showWarn = showWarnIfError.get();
            if(Boolean.TRUE.equals(showWarn)) log.error("Erro ao obter dependência: reference={}, qualifier={}, msg={}", reference.getName(), qualifier, "null dependency");

            return null;
        }catch (Exception e){
            if(showWarnIfError == null) showWarnIfError = () -> true;

            Boolean showWarn = showWarnIfError.get();
            if(Boolean.TRUE.equals(showWarn)) log.error("Erro ao obter dependência: reference={}, qualifier={}, msg={}", reference.getName(), qualifier, e.getMessage(), e);

            return null;
        }
    }
    
    
    private ParamtrizedObject extractType(Field field){
        Class<?> fieldType = field.getType();
        Type genericType = field.getGenericType();

        if (genericType instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length == 1 && (typeArgs[0] instanceof Class || typeArgs[0] instanceof ParameterizedType)) {
                return new ParamtrizedObject(fieldType, typeArgs[0], true);
            }
        }

        return new ParamtrizedObject(fieldType, fieldType, false);
    }

    private ParamtrizedObject extractType(Parameter parameter){
        Class<?> fieldType = parameter.getType();
        Type genericType = parameter.getParameterizedType();

        if (genericType instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length == 1 && (typeArgs[0] instanceof Class || typeArgs[0] instanceof ParameterizedType)) {
                return new ParamtrizedObject(fieldType, typeArgs[0], true);
            }
        }

        return new ParamtrizedObject(fieldType, fieldType, false);
    }


    private void injectVariable(Field variable, Object instance){
        try{
            final ParamtrizedObject paramtrizedObject = extractType(variable);

            if(!variable.canAccess(instance)){
                variable.setAccessible(true);
            }

            if(variable.isAnnotationPresent(Value.class)){
                Object resolved = resolveValueAnnotation(variable);
                variable.set(instance, resolved);
                return;
            }

            if(paramtrizedObject.isParametrized()){
                Object target = getDependencyObjectByField(variable, instance);
                variable.set(instance, target);
            }else{
                Object targetInstance = getObjectToInjectVariable(variable, paramtrizedObject.getBaseClass());
                variable.set(instance, targetInstance);
            }

        }catch (Exception e){
            String instanceClassName = (instance != null) ? instance.getClass().getName() : "[instancia nula]";

            if(!isInjectionWarnDisabled(variable, instance)){
                log.error("Erro ao injetar variável '{}' na classe '{}'. Causa: {}",
                        variable.getName(),
                        instanceClassName,
                        e.getMessage(),
                        e
                );
            }
        }
    }

    private boolean isInjectionWarnDisabled(AnnotatedElement element, Object instance) {
        if (element.isAnnotationPresent(DisableInjectionWarn.class)) {
            return true;
        }

        Class<?> targetClass = instance instanceof Class<?> clazz
                ? clazz
                : instance != null ? instance.getClass() : null;

        return targetClass != null && targetClass.isAnnotationPresent(DisableInjectionWarn.class);
    }

    private Object getObjectToInjectVariable(Field variable, Class<?> clazzVariable) throws Exception{
        final String qualifierName = getQualifierName(variable);
        Map<String, Dependency> mapOfDependency = getDependencyMap(clazzVariable);
        if(mapOfDependency.isEmpty() && childrenRegistration){
            try {
                registerDependency(clazzVariable);
            } catch (InvalidClassRegistrationException e) {
                throw new DependencyContainerRuntimeException(e);
            }
            mapOfDependency = getDependencyMap(clazzVariable);
        }
        Dependency dependencyObject = resolveWithPrimary(clazzVariable, mapOfDependency, qualifierName);
        if(dependencyObject == null){
            throw new DependencyContainerException("Dependencia não encontrada para: "+clazzVariable);
        }
        return dependencyObject.getDependency();
    }

    /**
     * Resolve uma dependência respeitando {@link dtm.di.annotations.Primary}.
     *
     * Quando o qualificador é o "default" (injeção sem {@code @Qualifier}) e há vários candidatos,
     * a entrada anotada com {@code @Primary} vence sobre a registrada como "default".
     * Para qualificadores explícitos, mantém o lookup direto.
     */
    private static final String PRIMARY_QUALIFIER_PREFIX = "$primary$:";

    private Dependency resolveWithPrimary(Class<?> reference, Map<String, Dependency> map, String qualifier){
        if(map == null || map.isEmpty()) return null;
        boolean isDefaultLookup = qualifier == null || qualifier.isEmpty() || "default".equalsIgnoreCase(qualifier);
        if(isDefaultLookup){
            Dependency primary = primaryDependencyIndex.get(reference);
            if(primary != null) return primary;
        }
        Dependency direct = map.get(qualifier);
        if(direct != null) return direct;
        if(AsyncComponent.class.equals(reference)){
            List<Dependency> matches = map.values().stream()
                    .filter(dependency -> qualifier.equals(dependency.getQualifier()))
                    .toList();
            return (matches.size() == 1) ? matches.getFirst() : null;
        }
        return null;
    }

    private void registerExternalBeenNoSinglenton(
            @NonNull Object instance,
            Method method,
            String qualifier,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException{
        Class<?> beenClass = instance.getClass();

        try{
            Constructor<?> defaultConstructor = beenClass.getDeclaredConstructor();
            if (!Modifier.isPublic(defaultConstructor.getModifiers())) {
                throw new InvalidClassRegistrationException(
                        "Bean externo não-singleton (" + beenClass.getName() + ") deve ter um construtor vazio público.",
                        beenClass,
                        null
                );
            }
            ServiceBean serviceBean = new ServiceBean(beenClass, 0, isAopEnabled(instance.getClass()));

            loadBeen(serviceBean, new HashSet<>(), qualifier, registration);
        }catch (NoSuchMethodException e) {
            throw new InvalidClassRegistrationException(
                    "Bean externo não-singleton (" + beenClass.getName() + ") deve possuir um construtor vazio.",
                    beenClass,
                    e
            );
        }
    }

    private void registerObject(@NonNull Object dependency) throws InvalidClassRegistrationException{
        registerObject(dependency, "default");
    }

    private void registerObject(@NonNull Object dependency, boolean aop) throws InvalidClassRegistrationException{
        registerObject(dependency, "default", aop);
    }

    private void registerObject(@NonNull Object dependency, @NonNull String qualifier) throws InvalidClassRegistrationException {
        try {
            final Class<?> clazz = dependency.getClass();
            if(!isProfileActive(clazz)) return;
            final Object toRegistrate = isAopEnabled(clazz) ? proxyObject(dependency, clazz) : dependency;
            if(dependencyContainer.containsKey(clazz)) return;
            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(clazz, qualifier);
            DependencyObject dependencyObject = new DependencyObject(clazz, qualifier, true, () -> {return toRegistrate;}, toRegistrate);

            registerInContainer(
                    mapOfDependency,
                    clazz,
                    dependencyObject,
                    qualifier
            );
        }catch (Exception e) {
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + dependency.getClass()+ " ==> causa: "+e.getMessage(),
                    dependency.getClass(),
                    e
            );
        }
    }

    private void registerObject(@NonNull Object dependency, @NonNull String qualifier, boolean aop) throws InvalidClassRegistrationException {
        registerObject(dependency, qualifier, aop, null);
    }

    private void registerObject(
            @NonNull Object dependency,
            @NonNull String qualifier,
            boolean aop,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException {
        try {
            final Class<?> clazz = dependency.getClass();
            if(!isProfileActive(clazz)) return;
            final Object toRegistrate = aop ? proxyObject(dependency, clazz) : dependency;
            if(dependencyContainer.containsKey(clazz)) return;
            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(clazz, qualifier);
            DependencyObject dependencyObject = new DependencyObject(clazz, qualifier, true, () -> {return toRegistrate;}, toRegistrate);
            registerInContainer(
                    mapOfDependency,
                    clazz,
                    dependencyObject,
                    qualifier,
                    registration
            );
            trackExternalInstance(registration, clazz, toRegistrate, aop);
        }catch (Exception e) {
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + dependency.getClass()+ " ==> causa: "+e.getMessage(),
                    dependency.getClass(),
                    e
            );
        }
    }

    private void registerObjectFunction(@NonNull RegistrationFunction<?> registrationFunction, Boolean isAop){
        registerObjectFunction(registrationFunction, isAop, null);
    }

    private void registerObjectFunction(
            @NonNull RegistrationFunction<?> registrationFunction,
            Boolean isAop,
            ExternalComponentRegistration registration
    ){
        final Class<?> referenceClass = registrationFunction.getReferenceClass();
        final String qualifier = (registrationFunction.getQualifier().isEmpty()) ? "default" : registrationFunction.getQualifier();
        if(!isProfileActive(referenceClass)) return;

        try{
            Supplier<?> activatorFunction = () -> {
                boolean shouldApplyAop = (isAop != null) ? isAop : isAopEnabled(referenceClass);
                Object instance = registrationFunction.getFunction().get();

                if (instance == null) {
                    throw new InvalidClassRegistrationException("Instância inválida para " + referenceClass, referenceClass);
                }

                return shouldApplyAop ? proxyObject(instance, instance.getClass()) : instance;
            };

            if(dependencyContainer.containsKey(referenceClass)) return;
            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(referenceClass, qualifier);
            DependencyObject dependencyObject = new DependencyObject(referenceClass, qualifier, false, activatorFunction, activatorFunction);
            registerInContainer(
                    mapOfDependency,
                    referenceClass,
                    dependencyObject,
                    qualifier,
                    registration
            );
            trackExternalType(registration, referenceClass);
        }catch (Exception e) {
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + referenceClass+ " ==> causa: "+e.getMessage(),
                    referenceClass,
                    e
            );
        }
    }

    private void registerObjectFunction(@NonNull AsyncRegistrationFunction<?> asyncRegistrationFunction, Boolean isAop){
        registerObjectFunction(asyncRegistrationFunction, isAop, null);
    }

    private void registerObjectFunction(
            @NonNull AsyncRegistrationFunction<?> asyncRegistrationFunction,
            Boolean isAop,
            ExternalComponentRegistration registration
    ){
        final Class<?> referenceClass = asyncRegistrationFunction.getReferenceClass();
        final String qualifier = (asyncRegistrationFunction.getQualifier().isEmpty()) ? "default" : asyncRegistrationFunction.getQualifier();
        final ExecutorService executorService = (asyncRegistrationFunction.getExecutor() != null) ? asyncRegistrationFunction.getExecutor() : mainExecutor;
        if(!isProfileActive(referenceClass)) return;
        try{
            CompletableFuture<?> resolveComponentAsync = CompletableFuture.supplyAsync(() -> {
                boolean shouldApplyAop = ((isAop != null) ? isAop : isAopEnabled(referenceClass));
                Object instance = asyncRegistrationFunction.getFunction().get();

                if (instance == null) {
                    throw new InvalidClassRegistrationException("Instância inválida para " + referenceClass, referenceClass);
                }

                shouldApplyAop = shouldApplyAop && isAopEnabled(instance.getClass());
                return shouldApplyAop ? proxyObject(instance, instance.getClass()) : instance;
            }, executorService);

            trackExternalAsyncTask(registration, referenceClass, Boolean.TRUE.equals(isAop), resolveComponentAsync);

            Supplier<?> activatorFunction = () -> {
              return new AsyncComponentStorage<>(referenceClass, qualifier, resolveComponentAsync);
            };

            String registrationKey = asyncRegistrationKey(referenceClass, qualifier);
            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(
                    AsyncComponent.class,
                    registrationKey,
                    referenceClass
            );
            if(mapOfDependency.values().stream().anyMatch(d ->
                    d.getDependencyClass().equals(referenceClass) && d.getQualifier().equals(qualifier))){
                return;
            }
            DependencyObject dependencyObject = new DependencyObject(referenceClass, qualifier, false, activatorFunction, activatorFunction);

            registerInContainer(
                    mapOfDependency,
                    AsyncComponent.class,
                    dependencyObject,
                    registrationKey,
                    false,
                    registration
            );
        }catch (Exception e) {
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + referenceClass+ " ==> causa: "+e.getMessage(),
                    referenceClass,
                    e
            );
        }

    }

    private void registerInContainer(
            @NonNull final Map<String, Dependency> listOfDependency,
            @NonNull Class<?> classToRegister,
            @NonNull DependencyObject dependencyObject ,
            @NonNull String qualifier
    ) throws InvalidClassRegistrationException {
        registerInContainer(listOfDependency, classToRegister, dependencyObject, qualifier, true, null);
    }

    private void registerInContainer(
            @NonNull final Map<String, Dependency> listOfDependency,
            @NonNull Class<?> classToRegister,
            @NonNull DependencyObject dependencyObject ,
            @NonNull String qualifier,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException {
        registerInContainer(listOfDependency, classToRegister, dependencyObject, qualifier, true, registration);
    }

    private void registerInContainer(
            @NonNull final Map<String, Dependency> listOfDependency,
            @NonNull Class<?> classToRegister,
            @NonNull DependencyObject dependencyObject ,
            @NonNull String qualifier,
            boolean registerSubTypes
    ) throws InvalidClassRegistrationException {
        registerInContainer(listOfDependency, classToRegister, dependencyObject, qualifier, registerSubTypes, null);
    }

    private void registerInContainer(
            @NonNull final Map<String, Dependency> listOfDependency,
            @NonNull Class<?> classToRegister,
            @NonNull DependencyObject dependencyObject ,
            @NonNull String qualifier,
            boolean registerSubTypes,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException {
        indexPrimary(classToRegister, dependencyObject, qualifier, registration);
        listOfDependency.put(qualifier, dependencyObject);
        dependencyContainer.put(classToRegister, listOfDependency);
        trackExternalSlot(registration, classToRegister, qualifier, dependencyObject);
        if(registerSubTypes)registerSubTypes(classToRegister, dependencyObject, qualifier, registration);
    }

    private void indexPrimary(
            Class<?> classToRegister,
            Dependency dependencyObject,
            String qualifier,
            ExternalComponentRegistration registration
    ) throws InvalidClassRegistrationException {
        if(!isPrimaryDependency(dependencyObject, qualifier)) return;

        Set<Class<?>> types = new LinkedHashSet<>();
        Class<?> dependencyClass = dependencyObject.getDependencyClass();
        if(dependencyClass != null && classToRegister.isAssignableFrom(dependencyClass)){
            types.add(classToRegister);
        }
        types.addAll(dependencyObject.getDependencyClassInstanceTypes());

        for(Class<?> type : types){
            Dependency existing = primaryDependencyIndex.putIfAbsent(type, dependencyObject);
            if(existing != null && existing != dependencyObject){
                throw new InvalidClassRegistrationException(
                        "Mais de um bean @Primary foi registrado para " + type.getName() + ". Mantenha apenas um bean principal para esse tipo.",
                        dependencyObject.getDependencyClass()
                );
            }
            if(registration != null && existing == null){
                registration.addPrimaryType(type, dependencyObject);
            }
        }
    }

    private boolean isPrimaryDependency(Dependency dependencyObject, String qualifier){
        if(qualifier != null && qualifier.startsWith(PRIMARY_QUALIFIER_PREFIX)) return true;
        Class<?> depClass = dependencyObject.getDependencyClass();
        return depClass != null && depClass.isAnnotationPresent(dtm.di.annotations.Primary.class);
    }

    private Map<String, Dependency> getDependencyMap(Class<?> referenceClass) {
        return dependencyContainer.computeIfAbsent(referenceClass, k -> new ConcurrentHashMap<>());
    }

    private String asyncRegistrationKey(Class<?> referenceClass, String qualifier){
        return qualifier + "|" + referenceClass.getName();
    }

    private Map<String, Dependency> getDependencyMapAndValidDependency(Class<?> referenceClass, @NonNull String qualifier){
        return getDependencyMapAndValidDependency(referenceClass, qualifier, referenceClass);
    }

    private Map<String, Dependency> getDependencyMapAndValidDependency(Class<?> referenceClass, @NonNull String qualifier, boolean registerAutoInject){
        return getDependencyMapAndValidDependency(referenceClass, qualifier, referenceClass, registerAutoInject);
    }

    private Map<String, Dependency> getDependencyMapAndValidDependency(Class<?> referenceClass, @NonNull String qualifier, Class<?> validClass){
        return getDependencyMapAndValidDependency(referenceClass, qualifier, validClass, true);
    }

    private Map<String, Dependency> getDependencyMapAndValidDependency(Class<?> referenceClass, @NonNull String qualifier, Class<?> validClass, boolean registerAutoInject){
        Map<String, Dependency> mapOfDependency = dependencyContainer.computeIfAbsent(referenceClass, k -> new ConcurrentHashMap<>());
        validQualifier(mapOfDependency, qualifier, validClass);
        return mapOfDependency;
    }

    private void registerSubTypes(
            @NonNull Class<?> clazz,
            @NonNull DependencyObject dependencyObject,
            @NonNull String qualifier,
            ExternalComponentRegistration registration
    ){
        if (clazz.equals(Object.class) || clazz.isInterface()) {
            return;
        }
        Class<?> superClass = clazz.getSuperclass();
        Class<?>[] interfaces = clazz.getInterfaces();

        if (
                superClass != null &&
                !superClass.equals(Object.class) &&
                !superClass.isInterface() &&
                !clazz.isAnnotationPresent(ExcludeRootRegistration.class)
        ) {
            registerAlias(superClass, dependencyObject, qualifier, registration);
        }

        for(Class<?> interfaceObj : interfaces){
            if (!interfaceObj.equals(Object.class)) {
                registerAlias(interfaceObj, dependencyObject, qualifier, registration);
            }
        }

    }

    private void registerAlias(
            @NonNull Class<?> indexedType,
            @NonNull DependencyObject dependencyObject,
            @NonNull String qualifier,
            ExternalComponentRegistration registration
    ){
        Map<String, Dependency> registrations = dependencyContainer.computeIfAbsent(
                indexedType,
                ignored -> new ConcurrentHashMap<>()
        );

        if(registration == null){
            registrations.put(qualifier, dependencyObject);
            return;
        }

        Dependency previous = registrations.putIfAbsent(qualifier, dependencyObject);
        if(previous == null){
            trackExternalSlot(registration, indexedType, qualifier, dependencyObject);
        }
    }

    private Object proxyObject(Object realInstance, Class<?> clazz){
        try{
            if(executeProxy(realInstance)) return ProxyFactory.newProxyObject(realInstance, clazz, this);
        }catch (Exception e){
            log.error("Erro ao criar o proxy para a classe {}: {}", clazz.getName(), e.getMessage(), e);
        }

        return realInstance;
    }

    private boolean executeProxy(Object instance){
        if(instance == null) return false;
        return !instance.getClass().isAnnotationPresent(DisableAop.class);
    }

    private void loadSystemClasses(){
        this.classFinderConfigurations = new ClassFinderConfigurations() {};
        this.classFinderConfigurations.getIgnoreJarsTerms().addAll(
                List.of(
                    "lombok", "byte-buddy", "logback-classic", "slf4j-api", "classfinder"
                )
        );
        this.classFinderConfigurations.getIgnorePackges().addAll(
                List.of(
                    "net.bytebuddy", "ch.qos.logback", "lombok"
                )
        );
        if(mainClass != null){
            loadedSystemClasses.addAll(classFinder.find(mainClass, classFinderConfigurations));
        }else{
            loadedSystemClasses.addAll(classFinder.find(classFinderConfigurations));
        }
    }

    private void injectExternalModules(){
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Set<Class<?>> discoveredClasses = ConcurrentHashMap.newKeySet();
        for (Class<?> clazz : loadedSystemClasses){
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                scanRecursive(clazz, new HashSet<>(), discoveredClasses);
            }, mainExecutor);
            futures.add(task);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        loadedSystemClasses.addAll(discoveredClasses);
    }

    private void scanRecursive(Class<?> clazz, Set<Class<?>> visited, Set<Class<?>> globalResult){
        Import importAnnotation = AnnotationsUtils.getMetaAnnotation(clazz, Import.class);

        if(importAnnotation != null){
            Class<?>[] configs = importAnnotation.value();

            for (Class<?> toImport : configs) {
                globalResult.add(toImport);
                scanRecursive(toImport, visited, globalResult);
            }

        }

    }

    private boolean filterConcreteBean(Class<?> clazz, Class<? extends Annotation> annotation){
        return hasMetaAnnotation(clazz, annotation) && isConcreteClass(clazz);
    }

    private boolean filterConcreteBeanAndActive(Class<?> clazz, Class<? extends Annotation> annotation){
        if (!isConcreteClass(clazz) || !hasMetaAnnotation(clazz, annotation)) {
            return false;
        }

        return isProfileActive(clazz);
    }

    private boolean isProfileActive(Class<?> clazz){
        Profile profile = AnnotationsUtils.getMetaAnnotation(clazz, Profile.class);

        return isProfileActive(profile);
    }

    private boolean isProfileActive(Method method){
        Profile profile = AnnotationsUtils.getMetaAnnotation(method, Profile.class);

        return isProfileActive(profile);
    }

    private boolean isProfileActive(Profile profile){

        if (profile != null) {
            List<String> selectedProfiles = normalizeProfiles(profile.value());
            return selectedProfiles.isEmpty() || selectedProfiles.stream().anyMatch(this.profiles::contains);
        }

        return true;
    }

    private boolean isConcreteClass(Class<?> clazz){
        return !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()) && !clazz.isEnum() && !clazz.isRecord();
    }
    
    private void executePostCreationMethod(Class<?> clazz, Object instance){
        List<Method> postCreationMethods = getPostCreationMethod(clazz);

        for(Method method: postCreationMethods){
            try{
                method.setAccessible(true);
                invokeMethod(method, instance);
            }catch (Exception e){
                log.error("Erro ao executar metodo: {}:{} do PostCreation", method.getName(), clazz, e);
            }
        }

    }

    private List<Method> getPostCreationMethod(Class<?> clazz){
        List<Method> postCreationMethods = new ArrayList<>(ReflectionCache.methodsWithAnnotation(clazz, PostCreation.class));

        postCreationMethods.sort(Comparator.comparingInt(
                m -> m.getAnnotation(PostCreation.class).order()
        ));

        return postCreationMethods;
    }

    private void invokeMethod(Method method, Object instance) throws Exception{
        int paramCount = method.getParameterCount();

        if(paramCount > 0){
            invokeMethodNoArgs(method, instance);
            return;
        }

        invokeMethodWithArgs(method, instance, paramCount);
    }

    private void invokeMethodWithArgs(Method method, Object instance, int paramCount) throws Exception{
        Parameter[] paramTypeList = method.getParameters();
        CompletableFuture<?>[] futures = new CompletableFuture[paramCount];

        for (int i = 0; i < paramCount; i++) {
            final int index = i;
            Parameter parameter = paramTypeList[i];
            String qualifier = getQualifierName(parameter);

            futures[i] = CompletableFuture.supplyAsync(() ->
                    getDependency(parameter.getType(), qualifier)
            ).thenApply(dep -> {
                return dep;
            });
        }

        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures);
        Object[] args = allDone.thenApply(v ->
                Arrays.stream(futures)
                        .map(CompletableFuture::join)
                        .toArray()
        ).join();


        method.invoke(instance, args);
    }

    private void invokeMethodNoArgs(Method method, Object instance) throws Exception{
        method.invoke(instance);
    }

    private Object[] tryResolveConstructorArgs(Parameter[] parameters, Object[] extraArgs, List<Parameter> failedParams, Class<?> clazz) {
        Object[] args = new Object[parameters.length];

        List<Object> extras = new ArrayList<>();
        if (extraArgs != null) {
            Collections.addAll(extras, extraArgs);
        }

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Class<?> paramType = parameter.getType();

            Object matchedExtra = null;
            Iterator<Object> iterator = extras.iterator();
            while (iterator.hasNext()) {
                Object candidate = iterator.next();
                if (candidate != null && paramType.isAssignableFrom(candidate.getClass())) {
                    matchedExtra = candidate;
                    iterator.remove();
                    break;
                }
            }

            if (matchedExtra != null) {
                args[i] = matchedExtra;
            } else {
                Object injected = getDependecyObjectByParam(parameter, clazz);
                if (injected == null) {
                    if (failedParams != null) {
                        failedParams.add(parameter);
                    }
                    return null;
                }
                args[i] = injected;
            }
        }

        return args;
    }

    private Constructor<?> getSelectedConstructor(Constructor<?>[] constructors, Class<?> clazz){
        if(constructors == null || constructors.length == 0) throw new NewInstanceException("construtor não encontrado para: "+clazz, clazz);

        return Arrays.stream(constructors)
                .filter(c -> c.isAnnotationPresent(MainConstructor.class))
                .findFirst()
                .orElse(constructors[0]);
    }

    private <T> T newInstance(Class<T> referenceClass, boolean aop) throws NewInstanceException {
        try{
            return (T)createObject(referenceClass, aop);
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    private boolean isAopEnabled(Class<?> clazz){
        if(!isAopEnabled()) return false;
        if(clazz.isAnnotationPresent(DisableAop.class) || clazz.isAnnotationPresent(Aspect.class)) return false;

        return aop;
    }

    private boolean isAopEnabled(Method method){
        if(!isAopEnabled()) return false;
        if(method.isAnnotationPresent(DisableAop.class)) return false;
        return aop;
    }

    private List<Set<Class<?>>> groupByDependencyLayer(Set<Class<?>> serviceLoadedClass, Map<Class<?>, Set<Class<?>>> dependencyGraph) {
        DependencyLayerResolver dependencyLayerResolver = new DependencyLayerResolver(serviceLoadedClass, dependencyGraph);
        return dependencyLayerResolver.resolveLayers();
    }

    private boolean isParallelInjection(int injectionSize){
        return (injectionStrategy.get() == InjectionStrategy.ADAPTIVE)
                ? injectionSize > 10
                : InjectionStrategy.PARALLEL == injectionStrategy.get();
    }

    private void injectDependenciesParallel(Object instance, List<Field> listOfRegistration){
        try{
            final List<CompletableFuture<?>> tasks = new ArrayList<>();
            ExecutorService executorService = (listOfRegistration.size() > 10) ? mainExecutor : mainVirtualExecutor;
            for (Field variable : listOfRegistration) {
                CompletableFuture<?> task = CompletableFuture.runAsync(() -> {
                    injectVariable(variable, instance);
                }, executorService);
                tasks.add(task);
            }

            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get();
        } catch (Exception e) {
            log.error("Falha geral na injeção paralela para a instância {}",
                    instance.getClass().getName(), e);
        }
    }

    private void injectDependenciesSequential(Object instance, List<Field> listOfRegistration){
        for (Field variable : listOfRegistration) {
            injectVariable(variable, instance);
        }
    }


    private <T> T getDependency(Class<T> reference, Supplier<Boolean> showWarnIfError) {
        return getDependency(reference, getQualifierName(reference), showWarnIfError, null);
    }

    private <T> T getDependency(Class<T> reference, String qualifier, Supplier<Boolean> showWarnIfError) {
        return getDependency(reference, qualifier, showWarnIfError, null);
    }

    private <T> T getDependency(Class<T> reference, Supplier<Boolean> showWarnIfError, String origin) {
        return getDependency(reference, getQualifierName(reference), showWarnIfError, origin);
    }

    private <T> T getDependency(Class<T> reference, String qualifier, Supplier<Boolean> showWarnIfError, String origin) {
        try{
            final Map<String, Dependency> listOfDependency = getDependencyMap(reference);
            final Dependency dependencyObject = resolveWithPrimary(reference, listOfDependency, qualifier);

            if(dependencyObject == null){
                throw new DependencyInjectionException("Erro ao obter dependência: reference="+reference+", qualifier="+qualifier);
            }
            Object instance = dependencyObject.getDependency();
            return reference.cast(instance);
        }catch (Exception e){
            if(showWarnIfError == null) showWarnIfError = () -> true;

            Boolean showWarn = showWarnIfError.get();
            if(Boolean.TRUE.equals(showWarn)) log.error("Erro ao obter dependência: reference={}, qualifier={}, origem={}, msg={}", reference.getName(), qualifier, origin, e.getMessage(), e);

            return null;
        }
    }

    private String describeInjectionOrigin(AnnotatedElement element, Object instance) {
        String owner = instance instanceof Class<?> clazz
                ? clazz.getName()
                : instance != null ? instance.getClass().getName() : "desconhecido";

        if (element instanceof Field field) {
            return "campo '" + field.getName() + "' de " + owner;
        }

        if (element instanceof Parameter parameter) {
            Executable executable = parameter.getDeclaringExecutable();
            String member = executable instanceof Constructor<?>
                    ? "construtor"
                    : "método '" + executable.getName() + "'";
            return member + " de " + owner + ", parâmetro '" + parameter.getName()
                    + "' (" + parameter.getType().getName() + ")";
        }

        return "elemento de " + owner;
    }

    private <T> List<T> getDependencyListSelf(Class<T> reference) {
        try{
            return getDependencyMap(reference).values().stream().map(d -> {
                try{
                    return reference.cast(d.getDependency());
                } catch (Exception e) {
                    log.error(
                            "Falha ao converter dependência. reference={}, dependencyClass={}, msg={}",
                            reference.getName(),
                            d.getDependency() != null ? d.getDependency().getClass().getName() : "null",
                            e.getMessage(),
                            e
                    );
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());
        }catch (Exception e){
            log.error("Erro ao obter lista de dependências para reference={}, msg={}",
                    reference.getName(), e.getMessage(), e);
            return null;
        }
    }

    private void injectDependenciesInternal(Object instance) {
        if(instance == null) return;

        final Class<?> clazz = instance.getClass();

        List<Field> injectFields = getAllFieldWithAnnotation(clazz, Inject.class);
        List<Field> valueFields = getAllFieldWithAnnotation(clazz, Value.class);

        List<Field> listOfRegistration;
        if(valueFields.isEmpty()){
            listOfRegistration = injectFields;
        }else{
            Set<Field> dedup = new LinkedHashSet<>(injectFields);
            dedup.addAll(valueFields);
            listOfRegistration = new ArrayList<>(dedup);
        }

        if(isParallelInjection(listOfRegistration.size())){
            injectDependenciesParallel(instance, listOfRegistration);
        }else{
            injectDependenciesSequential(instance, listOfRegistration);
        }
    }

    private Object resolveValueAnnotation(Field variable) {
        Value value = variable.getAnnotation(Value.class);
        AppSettings settings = resolveAppSettings();
        if(settings == null){
            log.warn("AppSettings indisponível ao resolver @Value em {}#{}",
                    variable.getDeclaringClass().getName(), variable.getName());
            return null;
        }
        return resolveValue(value, variable.getType(), variable.getGenericType(), settings);
    }

    private Object resolveValueAnnotation(Parameter parameter) {
        Value value = parameter.getAnnotation(Value.class);
        AppSettings settings = resolveAppSettings();
        if(settings == null){
            log.warn("AppSettings indisponível ao resolver @Value no parâmetro '{}' de {}",
                    parameter.getName(), parameter.getDeclaringExecutable());
            return null;
        }
        return resolveValue(value, parameter.getType(), parameter.getParameterizedType(), settings);
    }

    private Object resolveValue(Value value, Class<?> type, Type genericType, AppSettings settings) {
        String key = value.key();
        String def = value.defaultValue();

        if(type == String.class){
            return settings.getString(key, def);
        }
        if(type == int.class || type == Integer.class){
            return settings.getInt(key, parseInt(def, 0));
        }
        if(type == long.class || type == Long.class){
            return settings.getLong(key, parseLong(def, 0L));
        }
        if(type == double.class || type == Double.class){
            return settings.getDouble(key, parseDouble(def, 0d));
        }
        if(type == float.class || type == Float.class){
            return (float) settings.getDouble(key, parseDouble(def, 0d));
        }
        if(type == boolean.class || type == Boolean.class){
            return settings.getBoolean(key, Boolean.parseBoolean(def));
        }
        if(type == short.class || type == Short.class){
            return (short) settings.getInt(key, parseInt(def, 0));
        }
        if(type == byte.class || type == Byte.class){
            return (byte) settings.getInt(key, parseInt(def, 0));
        }

        if (genericType instanceof ParameterizedType
                && (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))) {
            return settings.getObject(key, genericType);
        }

        return settings.getObject(key, type);
    }

    private static int parseInt(String s, int fallback){
        if(s == null || s.isEmpty()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static long parseLong(String s, long fallback){
        if(s == null || s.isEmpty()) return fallback;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static double parseDouble(String s, double fallback){
        if(s == null || s.isEmpty()) return fallback;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private AppSettings resolveAppSettings(){
        Map<String, Dependency> map = dependencyContainer.get(AppSettings.class);
        if(map != null && !map.isEmpty()){
            Object instance = map.values().iterator().next().getDependency();
            if(instance instanceof AppSettings appSettings) return appSettings;
        }
        return null;
    }

    /**
     * Registra o {@link AppSettings} padrão (lê {@code settings.json} no working dir) caso
     * o usuário não tenha registrado o seu via {@code @Configuration}. Idempotente.
     */
    private void registerAppSettingsIfAbsent(){
        try{
            Map<String, Dependency> existing = dependencyContainer.get(AppSettings.class);
            if(existing != null && !existing.isEmpty()) return;

            JsonAppSettings settings = new JsonAppSettings(
                    JsonAppSettings.DEFAULT_RESOURCE_NAME,
                    profiles.toArray(String[]::new)
            );
            registerObject(settings, "default", false);
        }catch (Exception e){
            log.error("Falha ao registrar AppSettings padrão: {}", e.getMessage(), e);
        }
    }

}
