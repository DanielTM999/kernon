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
import dtm.di.settings.ContainerDefaults;
import dtm.di.settings.JsonAppSettings;
import dtm.di.annotations.settings.Value;
import dtm.di.core.ClassFinderDependencyContainer;
import dtm.di.core.DependencyContainer;
import dtm.di.core.AmbiguityPolicy;
import dtm.di.core.InjectionStrategy;
import dtm.di.core.PrototypeListenerPolicy;
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
import dtm.di.storage.external.GenericRegistrationSlot;
import dtm.di.storage.external.ExternalComponentRegistration;
import dtm.di.storage.external.ExternalLoadBatch;
import dtm.di.storage.lazy.Lazy;
import dtm.di.common.reflection.GenericTypes;
import dtm.di.common.reflection.WrapperTypes;
import dtm.di.exceptions.AmbiguousDependencyException;
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dtm.di.common.AnnotationsUtils.hasMetaAnnotation;
import static dtm.di.common.AnnotationsUtils.getAllFieldWithAnnotation;

@DisableAop
@Slf4j
@SuppressWarnings("unchecked")
public class DependencyContainerStorage implements DependencyContainer, ClassFinderDependencyContainer {

    private static final String INJECTION_STRATEGY_PROPERTY = "dependencyContainer.injectionStrategy";
    private static final int GRAPH_UNWRAP_DEPTH_LIMIT = 8;
    private static final String AMBIGUITY_POLICY_PROPERTY = "dependencyContainer.ambiguityPolicy";
    private static final String GENERIC_RESOLUTION_PROPERTY = "dependencyContainer.genericResolution";
    private static final String PROTOTYPE_LISTENER_POLICY_PROPERTY = "dependencyContainer.prototypeListenerPolicy";

    private final ExecutorService mainExecutor;
    private final ExecutorService mainVirtualExecutor;

    private final AtomicReference<InjectionStrategy> injectionStrategy;
    private final AtomicBoolean injectionStrategyConfiguredProgrammatically;
    private final Object injectionStrategyConfigurationLock;

    private final AtomicReference<AmbiguityPolicy> ambiguityPolicy;
    private final AtomicBoolean ambiguityPolicyConfiguredProgrammatically;
    private final Object ambiguityPolicyConfigurationLock;

    private final AtomicBoolean genericResolutionEnabled;
    private final AtomicBoolean genericResolutionConfiguredProgrammatically;
    private final Object genericResolutionConfigurationLock;

    private final AtomicReference<PrototypeListenerPolicy> prototypeListenerPolicy;
    private final AtomicBoolean prototypeListenerPolicyConfiguredProgrammatically;
    private final Object prototypeListenerPolicyConfigurationLock;

    private final Map<Class<?>, Map<String, Dependency>> dependencyContainer;
    private final Map<Class<?>, Dependency> primaryDependencyIndex;
    private final Map<String, Map<String, Dependency>> genericDependencyIndex;
    private final Map<AliasSlot, Set<Class<?>>> contestedAliases;
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

    private static final int DEPENDENCY_GRAPH_PARALLEL_THRESHOLD = 16;
    private static final int CLASS_SCAN_PARALLEL_THRESHOLD = 32;

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
        this.genericDependencyIndex = new ConcurrentHashMap<>();
        this.contestedAliases = new ConcurrentHashMap<>();
        this.ambiguityPolicy = new AtomicReference<>(AmbiguityPolicy.FAIL_FAST);
        this.ambiguityPolicyConfiguredProgrammatically = new AtomicBoolean(false);
        this.ambiguityPolicyConfigurationLock = new Object();
        this.genericResolutionEnabled = new AtomicBoolean(true);
        this.genericResolutionConfiguredProgrammatically = new AtomicBoolean(false);
        this.genericResolutionConfigurationLock = new Object();
        this.prototypeListenerPolicy = new AtomicReference<>(PrototypeListenerPolicy.SKIP);
        this.prototypeListenerPolicyConfiguredProgrammatically = new AtomicBoolean(false);
        this.prototypeListenerPolicyConfigurationLock = new Object();
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
        this.mainClass = mainClass;
        this.profiles = resolveProfiles(profiles);
        this.classFinderConfigurations = getFindConfigurations();
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
            SystemClassification classification = classifySystemClasses();
            filterServiceClass(classification);
            filterExternalsBeens(classification);
            selfInjection();
            loaded.set(true);
            registerExternalBeens(externalBeenBefore, null, null);
            registerAppSettingsIfAbsent();
            applyDeclarativeInjectionStrategy();
            applyDeclarativeAmbiguityPolicy();
            applyDeclarativeGenericResolution();
            applyDeclarativePrototypeListenerPolicy();
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
        final Set<Class<?>> normalized = expandExternalImports(normalizeExternalClasses(classes));
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
    private boolean shouldRegisterPrototypeListener(Class<?> listenerClass) {
        PrototypeListenerPolicy policy = prototypeListenerPolicy.get();

        if (policy == PrototypeListenerPolicy.REGISTER) {
            return true;
        }

        if (policy == PrototypeListenerPolicy.SKIP) {
            log.warn(
                    "Listener de evento '{}' e prototype e nao sera registrado no scan inicial: cada resolucao cria uma instancia diferente. Use @Singleton, registre o listener manualmente, ou mude '{}' para REGISTER.",
                    listenerClass.getName(),
                    PROTOTYPE_LISTENER_POLICY_PROPERTY
            );
        }

        return false;
    }

    private List<Object> getLoadedEventListeners() {
        List<Object> listeners = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Map.Entry<Class<?>, Map<String, Dependency>> entry : dependencyContainer.entrySet()) {
            if (!AnnotationsUtils.hasMetaAnnotation(entry.getKey(), Event.class)) {
                continue;
            }

            for (Dependency dependency : entry.getValue().values()) {
                if (!dependency.isSingleton() && !shouldRegisterPrototypeListener(entry.getKey())) {
                    continue;
                }

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
            genericDependencyIndex.clear();
            contestedAliases.clear();
            GenericTypes.clear();
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
    public void setAmbiguityPolicy(AmbiguityPolicy ambiguityPolicy) {
        synchronized (ambiguityPolicyConfigurationLock){
            this.ambiguityPolicyConfiguredProgrammatically.set(true);
            this.ambiguityPolicy.set(ambiguityPolicy != null ? ambiguityPolicy : AmbiguityPolicy.FAIL_FAST);
        }
    }

    @Override
    public void setPrototypeListenerPolicy(PrototypeListenerPolicy policy) {
        synchronized (prototypeListenerPolicyConfigurationLock){
            this.prototypeListenerPolicyConfiguredProgrammatically.set(true);
            this.prototypeListenerPolicy.set(policy != null ? policy : PrototypeListenerPolicy.SKIP);
        }
    }

    @Override
    public void setGenericResolutionEnabled(boolean enabled) {
        synchronized (genericResolutionConfigurationLock){
            this.genericResolutionConfiguredProgrammatically.set(true);
            this.genericResolutionEnabled.set(enabled);
        }
    }

    private AppSettings resolveDeclarativeSettings() {
        AppSettings settings = resolveAppSettings();
        if(settings == null){
            settings = new JsonAppSettings(
                    JsonAppSettings.DEFAULT_RESOURCE_NAME,
                    profiles.toArray(String[]::new)
            );
        }
        return settings;
    }

    private void applyDeclarativeAmbiguityPolicy() {
        applyDeclarativeEnum(
                AMBIGUITY_POLICY_PROPERTY,
                AmbiguityPolicy.class,
                AmbiguityPolicy.FAIL_FAST,
                ambiguityPolicyConfiguredProgrammatically,
                ambiguityPolicyConfigurationLock,
                ambiguityPolicy
        );
    }

    private void applyDeclarativePrototypeListenerPolicy() {
        applyDeclarativeEnum(
                PROTOTYPE_LISTENER_POLICY_PROPERTY,
                PrototypeListenerPolicy.class,
                PrototypeListenerPolicy.SKIP,
                prototypeListenerPolicyConfiguredProgrammatically,
                prototypeListenerPolicyConfigurationLock,
                prototypeListenerPolicy
        );
    }

    private <T extends Enum<T>> void applyDeclarativeEnum(
            String property,
            Class<T> type,
            T defaultValue,
            AtomicBoolean configuredProgrammatically,
            Object configurationLock,
            AtomicReference<T> target
    ) {
        if(configuredProgrammatically.get()) return;

        AppSettings settings = resolveDeclarativeSettings();
        if(!settings.has(property)) return;

        String configuredValue = settings.getString(property, "");
        String normalizedValue = (configuredValue == null)
                ? ""
                : configuredValue.trim().toUpperCase(Locale.ROOT);

        T declarativeValue;
        boolean invalidValue = false;
        try{
            declarativeValue = Enum.valueOf(type, normalizedValue);
        }catch (IllegalArgumentException e){
            declarativeValue = defaultValue;
            invalidValue = true;
        }

        synchronized (configurationLock){
            if(configuredProgrammatically.get()) return;
            target.set(declarativeValue);
            if(invalidValue){
                log.warn("Valor desconhecido '{}' em '{}'. Usando {}.", configuredValue, property, defaultValue);
            }
        }
    }

    private void applyDeclarativeGenericResolution() {
        if(genericResolutionConfiguredProgrammatically.get()) return;

        AppSettings settings = resolveDeclarativeSettings();
        if(!settings.has(GENERIC_RESOLUTION_PROPERTY)) return;

        boolean declarativeValue = settings.getBoolean(GENERIC_RESOLUTION_PROPERTY, true);

        synchronized (genericResolutionConfigurationLock){
            if(genericResolutionConfiguredProgrammatically.get()) return;
            this.genericResolutionEnabled.set(declarativeValue);
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
        throwIfUnload();
        return resolveDependencyAsync(reference, reference, qualifier, isAsyncComponent);
    }

    @Override
    public <T> AsyncComponent<T> getDependencyAsync(TypeRef<T> reference, boolean isAsyncComponent) {
        throwIfUnload();
        return getDependencyAsync(reference, getQualifierName(reference.getRawType()), isAsyncComponent);
    }

    @Override
    public <T> AsyncComponent<T> getDependencyAsync(TypeRef<T> reference, String qualifier, boolean isAsyncComponent) {
        throwIfUnload();
        return resolveDependencyAsync(reference.getRawType(), reference.getType(), qualifier, isAsyncComponent);
    }

    private <T> AsyncComponent<T> resolveDependencyAsync(
            Class<T> reference,
            Type requested,
            String qualifier,
            boolean isAsyncComponent
    ) {
        if(isAsyncComponent){
            return getAsyncComponent(reference, requested, qualifier, () -> true);
        }
        return new AsyncComponentStorage<>(reference, qualifier, CompletableFuture.supplyAsync(() -> {
            return reference.cast(resolveDependency(requested, qualifier, () -> true, describeAsyncOrigin(requested)));
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
            removeGenericIndexEntries(dependencyObj);
            removeAliasClaims(dependencyObj);
            GenericTypes.clear(dependencyObj.getDependencyClass());
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

    private Set<Class<?>> expandExternalImports(Set<Class<?>> classes){
        Set<Class<?>> expanded = new LinkedHashSet<>();
        for(Class<?> clazz : classes){
            collectImportedClasses(clazz, expanded);
        }
        return expanded;
    }

    private void collectImportedClasses(Class<?> clazz, Set<Class<?>> expanded){
        if(!expanded.add(clazz)) return;

        Import importAnnotation = AnnotationsUtils.getMetaAnnotation(clazz, Import.class);
        if(importAnnotation == null) return;

        for(Class<?> importedClass : importAnnotation.value()){
            collectImportedClasses(importedClass, expanded);
        }
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

        final Set<Class<?>> externalTypes = new LinkedHashSet<>(componentClasses);
        externalTypes.addAll(externalComponentRegistrations.keySet());
        final ServiceIndex knownExternalTypes = new ServiceIndex(externalTypes);

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

            List<GenericRegistrationSlot> genericSlots = registration.snapshotGenericSlots();
            for(int index = genericSlots.size() - 1; index >= 0; index--){
                removeGenericRegistrationSlot(genericSlots.get(index));
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
        removeAliasClaims(slot.dependency());

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
            GenericTypes.clear(registration.snapshotReflectionCacheClasses());
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
            ServiceIndex knownExternalTypes
    ){
        if(batch == null) return null;

        ExternalComponentRegistration registration = batch.registrationFor(ownerClass);
        registration.addDependencies(resolveExternalDependencies(ownerClass, knownExternalTypes));

        return registration;
    }

    private Set<Class<?>> resolveExternalDependencies(Class<?> clazz, ServiceIndex knownExternalTypes){
        if(knownExternalTypes == null || knownExternalTypes.isEmpty()) return Set.of();

        Set<Class<?>> dependencies = new LinkedHashSet<>();

        for(Class<?> dependency : getDependecyClassListOfClass(clazz, knownExternalTypes)){
            if(knownExternalTypes.contains(dependency)){
                dependencies.add(dependency);
            }
        }

        return dependencies;
    }

    private Set<Class<?>> resolveExternalMethodDependencies(List<Method> methods, ServiceIndex knownExternalTypes){
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
                    dependencies.addAll(knownExternalTypes.implementationsOf(type));
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
            ServiceIndex knownExternalTypes
    ) throws InvalidClassRegistrationException{
        for (Set<ServiceBean> layer : layers) {
            loadBeensInlayer(layer, batch, knownExternalTypes);
        }
    }

    private void loadBeensInlayer(
            Set<ServiceBean> layer,
            ExternalLoadBatch batch,
            ServiceIndex knownExternalTypes
    ) throws InvalidClassRegistrationException{
        if(layer.size() == 1){
            ServiceBean single = layer.iterator().next();
            loadBeen(
                    single,
                    new HashSet<>(),
                    getQualifierName(single.getClazz()),
                    externalRegistrationFor(batch, single.getClazz(), knownExternalTypes)
            );
            return;
        }

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
                            .declaredGenericType(been.getDeclaredGenericType())
                        .build()
                   : DependencyObject.builder()
                            .dependencyClass(dependency)
                            .qualifier(qualifier)
                            .singleton(false)
                            .creatorFunction(createActivationFunction(dependency, been.isAop()))
                            .singletonInstance(null)
                            .declaredGenericType(been.getDeclaredGenericType())
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

            final String registrationKey = asyncRegistrationKey(dependency, qualifier);
            if(getDependencyMap(AsyncComponent.class).containsKey(registrationKey)){
                return;
            }

            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(
                    AsyncComponent.class,
                    registrationKey,
                    dependency
            );

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
                    AsyncComponent.class,
                    dependencyObject,
                    registrationKey,
                    false,
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

    private record SystemClassification(
            Set<Class<?>> activeComponents,
            Set<Class<?>> activeAspects,
            Set<Class<?>> activeConfigurations,
            Set<Class<?>> allComponents
    ){}

    private SystemClassification classifySystemClasses(){
        final Set<Class<?>> activeComponents = ConcurrentHashMap.newKeySet();
        final Set<Class<?>> activeAspects = ConcurrentHashMap.newKeySet();
        final Set<Class<?>> activeConfigurations = ConcurrentHashMap.newKeySet();
        final Set<Class<?>> allComponents = ConcurrentHashMap.newKeySet();

        forEachClass(loadedSystemClasses, CLASS_SCAN_PARALLEL_THRESHOLD, clazz -> {
            if(!isConcreteClass(clazz)) return;

            boolean component = hasMetaAnnotation(clazz, Component.class);

            if(component){
                allComponents.add(clazz);
            }

            if(!isProfileActive(clazz)) return;

            if(component){
                activeComponents.add(clazz);
            }

            if(hasMetaAnnotation(clazz, Aspect.class)){
                activeAspects.add(clazz);
            }

            if(hasMetaAnnotation(clazz, Configuration.class)){
                activeConfigurations.add(clazz);
            }
        });

        return new SystemClassification(activeComponents, activeAspects, activeConfigurations, allComponents);
    }

    private void filterServiceClass(SystemClassification classification){
        final Set<Class<?>> serviceLoadedClassActive = ConcurrentHashMap.newKeySet();
        serviceLoadedClassActive.addAll(classification.activeComponents());
        serviceLoadedClassActive.addAll(classification.activeAspects());

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

        final ServiceIndex index = new ServiceIndex(serviceClasses);

        forEachClass(serviceClasses, DEPENDENCY_GRAPH_PARALLEL_THRESHOLD, clazz ->
                dependencyGraph.put(clazz, getDependecyClassListOfClass(clazz, index))
        );

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

            forEachClass(classSet, DEPENDENCY_GRAPH_PARALLEL_THRESHOLD, clazz ->
                    layer.add(new ServiceBean(clazz, layerOrder, isAopEnabled(clazz)))
            );

            layers.add(layer);
            order++;
        }

        return layers;
    }

    private void loadByPluginFolder(){
        for (String forderPath : foldersToLoad){
            loadedSystemClasses.addAll(classFinder.loadByDirectory(forderPath));
        }
    }

    private ClassFinderConfigurations getFindConfigurations(){
        try{
            String[] activeProfiles = profiles == null ? new String[0] : profiles.toArray(String[]::new);
            return ClassFinderConfigurationsStorage.fromSettings(
                    new JsonAppSettings(JsonAppSettings.DEFAULT_RESOURCE_NAME, activeProfiles)
            );
        }catch (Exception e){
            log.warn("Falha ao ler a configuração de scan do settings. Usando os padrões.", e);
            return new ClassFinderConfigurationsStorage();
        }
    }

    private String getQualifierName(@NonNull Class<?> clazz){
        String explicitQualifier = declaredQualifierOf(clazz);
        if(explicitQualifier != null) return explicitQualifier;

        String componentQualifier = componentAnnotationQualifierOf(clazz);
        if(componentQualifier != null) return componentQualifier;

        if(clazz.isAnnotationPresent(dtm.di.annotations.Primary.class)){
            return "$primary$:" + clazz.getName();
        }
        return  "default";
    }

    private String declaredQualifierOf(AnnotatedElement element){
        if(!element.isAnnotationPresent(Qualifier.class)) return null;
        return normalizeDeclaredQualifier(element.getAnnotation(Qualifier.class).value());
    }

    private String componentAnnotationQualifierOf(AnnotatedElement element){
        if(element.isAnnotationPresent(Service.class)){
            return normalizeDeclaredQualifier(element.getAnnotation(Service.class).qualifier());
        }
        if(element.isAnnotationPresent(Component.class)){
            return normalizeDeclaredQualifier(element.getAnnotation(Component.class).qualifier());
        }
        return null;
    }

    private String getQualifierName(@NonNull Field variable){
        return injectionPointQualifierOf(variable);
    }

    private String injectionPointQualifierOf(AnnotatedElement element){
        String explicitQualifier = declaredQualifierOf(element);
        if(explicitQualifier != null) return explicitQualifier;

        if(element.isAnnotationPresent(Inject.class)){
            String injectQualifier = normalizeDeclaredQualifier(element.getAnnotation(Inject.class).qualifier());
            if(injectQualifier != null) return injectQualifier;
        }

        return "default";
    }

    private String getQualifierName(@NonNull Parameter variable){
        return injectionPointQualifierOf(variable);
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
        String explicitQualifier = declaredQualifierOf(beenMethod);
        if(explicitQualifier != null) return explicitQualifier;

        String producerQualifier = componentAnnotationQualifierOf(beenMethod);
        if(producerQualifier != null) return producerQualifier;

        if(beenMethod.isAnnotationPresent(Primary.class)){
            return "$primary$:" + beenMethod.getDeclaringClass().getName() + "#" + beenMethod.getName();
        }
        return  "default";
    }

    private String normalizeDeclaredQualifier(String qualifier){
        if(qualifier == null || qualifier.isEmpty()) return null;
        return isDefaultQualifier(qualifier) ? null : qualifier;
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

    private static <T> void forEachClass(Collection<T> items, int parallelThreshold, Consumer<T> action){
        final int total = items.size();

        if(total == 0) return;

        if(total < parallelThreshold){
            for(T item : items){
                action.accept(item);
            }
            return;
        }

        items.parallelStream().forEach(action);
    }

    private Set<Class<?>> getDependecyClassListOfClass(Class<?> clazz, Set<Class<?>> serviceLoadedClass) {
        return getDependecyClassListOfClass(clazz, new ServiceIndex(serviceLoadedClass));
    }

    private Set<Class<?>> getDependecyClassListOfClass(Class<?> clazz, ServiceIndex serviceIndex) {
        Set<Class<?>> dependencies = new HashSet<>();

        for (Field field : ReflectionCache.fields(clazz)) {
            if (field.isAnnotationPresent(Inject.class)) {
                dependencies.addAll(isServiceDependency(field.getType(), field.getGenericType(), serviceIndex, field));
            }
        }

        for (Constructor<?> constructor : ReflectionCache.constructors(clazz)) {
            for (Parameter param : constructor.getParameters()) {
                if (param.isAnnotationPresent(Value.class)) {
                    continue;
                }
                dependencies.addAll(isServiceDependency(param.getType(), param.getParameterizedType(), serviceIndex, param));
            }
        }

        return dependencies;
    }

    private Type unwrapEagerWrapper(Type declaredType) {
        Type current = declaredType;

        for (int depth = 0; depth < GRAPH_UNWRAP_DEPTH_LIMIT; depth++) {
            if (!(current instanceof ParameterizedType parameterized)) return current;

            Class<?> raw = GenericTypes.raw(parameterized);
            if (!WrapperTypes.isEagerWrapper(raw)) return current;

            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length != 1) return current;

            current = arguments[0];
        }

        return current;
    }

    private Type unwrapAsyncComponent(Type declaredType) {
        if (!(declaredType instanceof ParameterizedType parameterized)) return declaredType;
        if (!AsyncComponent.class.equals(GenericTypes.raw(parameterized))) return declaredType;

        Type[] arguments = parameterized.getActualTypeArguments();
        return (arguments.length == 1) ? arguments[0] : declaredType;
    }

    private Set<Class<?>> isServiceDependency(Class<?> type, Type declaredType, ServiceIndex serviceIndex, Object extra) {
        Type effectiveType = unwrapEagerWrapper(unwrapAsyncComponent((declaredType != null) ? declaredType : type));
        Class<?> effectiveRaw = GenericTypes.raw(effectiveType);
        if (effectiveRaw == null) effectiveRaw = type;

        if(!effectiveRaw.isInterface() && !Modifier.isAbstract(effectiveRaw.getModifiers())){
            return Set.of(effectiveRaw);
        }

        List<Class<?>> candidates = serviceIndex.implementationsOf(effectiveRaw);

        if(candidates.isEmpty()) return Set.of();

        String qualifierElement = "default";
        if(extra instanceof Field field){
            qualifierElement = getQualifierName(field);
        }else if(extra instanceof Parameter parameter){
            qualifierElement = getQualifierName(parameter);
        }

        Set<Class<?>> byQualifier = new HashSet<>();
        for (Class<?> serviceClass : candidates) {
            if (serviceIndex.qualifierOf(serviceClass).equalsIgnoreCase(qualifierElement)) {
                byQualifier.add(serviceClass);
            }
        }

        boolean narrowByGenericArgument = genericResolutionEnabled.get()
                && effectiveType instanceof ParameterizedType
                && !GenericTypes.hasWildcard(effectiveType);

        if(!narrowByGenericArgument) return byQualifier;

        Type requested = effectiveType;
        Set<Class<?>> narrowed = byQualifier.stream()
                .filter(serviceClass -> declaresGenericSupertype(requested, serviceClass))
                .collect(Collectors.toCollection(HashSet::new));

        return narrowed.isEmpty() ? byQualifier : narrowed;
    }

    private final class ServiceIndex {

        private final Set<Class<?>> members;
        private final Map<Class<?>, List<Class<?>>> implementationsByType;
        private final Map<Class<?>, String> qualifierByClass;

        private ServiceIndex(Set<Class<?>> serviceClasses){
            this.members = serviceClasses;
            this.implementationsByType = new ConcurrentHashMap<>();
            this.qualifierByClass = new ConcurrentHashMap<>();
        }

        private List<Class<?>> implementationsOf(Class<?> type){
            return implementationsByType.computeIfAbsent(type, target -> {
                List<Class<?>> found = new ArrayList<>();

                for(Class<?> candidate : members){
                    if(candidate.isInterface() || Modifier.isAbstract(candidate.getModifiers())) continue;
                    if(target.isAssignableFrom(candidate)){
                        found.add(candidate);
                    }
                }

                return found;
            });
        }

        private String qualifierOf(Class<?> serviceClass){
            return qualifierByClass.computeIfAbsent(serviceClass, DependencyContainerStorage.this::getQualifierName);
        }

        private boolean isEmpty(){
            return members.isEmpty();
        }

        private boolean contains(Class<?> type){
            return members.contains(type);
        }
    }


    private void filterExternalsBeens(SystemClassification classification) throws InvalidClassRegistrationException{
        Set<Class<?>> configClasses = classification.activeConfigurations();

        if (configClasses.isEmpty()) {
            return;
        }

        Set<Class<?>> serviceClasses = classification.allComponents();
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
            ServiceIndex knownExternalTypes
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
                                registerObjectFunction(asyncRegistrationFunction, isAopEnabled(method), registration, producedGenericType(method));
                            }else if(result instanceof RegistrationFunction<?> registrationFunction){
                                registerObjectFunction(registrationFunction, isAopEnabled(method), registration, producedGenericType(method));
                            }else{
                                boolean aop = (isAopEnabled(method) && isAopEnabled(result.getClass()));
                                registerObject(result, qualifier, aop, registration, method.getGenericReturnType());
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

        registerObjectFunction(asyncProducer, isAopEnabled(method), registration, method.getGenericReturnType());
    }

    private void validateAsyncProducerDependency(Parameter parameter, Method consumer){
        if(AsyncComponent.class.equals(parameter.getType())){
            return;
        }

        String qualifier = getQualifierName(parameter);
        Type requested = parameter.getParameterizedType();
        Map<String, Dependency> synchronous = dependencyContainer.get(parameter.getType());
        boolean hasSynchronousDependency = resolveWithPrimary(parameter.getType(), synchronous, qualifier) != null;

        if(hasSynchronousDependency || !hasAsyncComponentRegistration(parameter.getType(), requested, qualifier)){
            return;
        }

        throw new InvalidClassRegistrationException(
                "O produtor '" + consumer.getName() + "' depende diretamente de "
                        + requested.getTypeName() + ", criado por um produtor @Async. "
                        + "Receba " + describeAsyncSuggestion(parameter) + ".",
                consumer.getDeclaringClass()
        );
    }

    private String describeAsyncSuggestion(Parameter parameter){
        Type requested = parameter.getParameterizedType();
        String inner = (requested instanceof ParameterizedType)
                ? requested.getTypeName()
                : parameter.getType().getSimpleName();
        return "AsyncComponent<" + inner + ">";
    }

    private boolean hasAsyncComponentRegistration(Class<?> referenceClass, Type requested, String qualifier){
        Map<String, Dependency> registrations = dependencyContainer.get(AsyncComponent.class);
        if(registrations == null || registrations.isEmpty()){
            return false;
        }

        return registrations.values().stream().anyMatch(dependency ->
                qualifier.equals(dependency.getQualifier())
                        && (referenceClass.equals(dependency.getDependencyClass())
                                || matchesGenerically(requested, dependency))
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
            return getParamObject(paramtrizedObject.getBaseClass(), paramtrizedObject.getParamType(), parameter, true, instance, disableWarn);
        }else{
            return resolveDependency(
                    paramtrizedObject.getDeclaredType(),
                    getQualifierName(parameter),
                    () -> !disableWarn,
                    describeInjectionOrigin(parameter, instance)
            );
        }
    }

    private Object getDependencyObjectByField(Field variable, Object instance){
        final ParamtrizedObject paramtrizedObject = extractType(variable);
        boolean disableWarn = isInjectionWarnDisabled(variable, instance);

        if(paramtrizedObject.isParametrized()){
            return getParamObject(paramtrizedObject.getBaseClass(), paramtrizedObject.getParamType(), variable, true, instance, disableWarn);
        }else{
            return resolveDependency(
                    paramtrizedObject.getDeclaredType(),
                    getQualifierName(variable),
                    () -> !disableWarn,
                    describeInjectionOrigin(variable, instance)
            );
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
            validateTerminalType(rawType, genericType, instance);
            return wrapInContainer(rawType, null, extractRawClass(genericType), genericType, qualifier, warn);
        }

        if (WrapperTypes.isBeanCollection(rawType) || CompositeDependency.class.equals(rawType)) {
            return wrapInContainer(rawType, null, extractRawClass(genericType), genericType, qualifier, warn);
        }

        Object innerObject = resolveNestedObject(genericType, element, qualifier, instance, warn);
        return wrapInContainer(rawType, innerObject, extractRawClass(genericType), genericType, qualifier, warn);
    }

    private Object resolveNestedObject(Type type, AnnotatedElement element, String qualifier, Object instance, boolean warn) {
        if (!(type instanceof ParameterizedType paramType)) {
            return resolveDependency(type, qualifier, () -> warn, describeInjectionOrigin(element, instance));
        }

        Class<?> nextRaw = extractRawClass(paramType);

        if (!WrapperTypes.isWrapper(nextRaw)) {
            return resolveDependency(type, qualifier, () -> warn, describeInjectionOrigin(element, instance));
        }

        Type innerType = paramType.getActualTypeArguments()[0];

        if (AsyncComponent.class.equals(nextRaw)) {
            validateTerminalType(nextRaw, innerType, instance);
            return wrapInContainer(nextRaw, null, extractRawClass(innerType), innerType, qualifier, warn);
        }

        return getParamObject(nextRaw, innerType, element, false, instance, !warn);
    }

    private void validateTerminalType(Class<?> nextRaw, Type innerType, Object instance) {
        if (!WrapperTypes.isWrapper(GenericTypes.raw(innerType))) {
            return;
        }

        String whereError = (instance instanceof String s) ? s :
                (instance != null ? instance.getClass().getName() : "unknown");

        throw new DependencyInjectionException(
                String.format("Nao e permitido aninhar wrappers dentro de '%s' (Encontrado: %s) em: %s",
                        nextRaw.getSimpleName(), innerType.getTypeName(), whereError)
        );
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
            Type targetType,
            String qualifier,
            boolean warn
    ) {
        if (containerType.equals(LazyDependency.class)) {
            return Lazy.of(() -> resolvedInner != null ? resolvedInner : getDependency(targetClass, qualifier, () -> warn));
        }

        if (containerType.equals(AsyncComponent.class)) {
            return (resolvedInner instanceof AsyncComponent<?>) ? resolvedInner : getAsyncComponent(targetClass, targetType, qualifier, () -> warn);
        }

        if (containerType.equals(CompositeDependency.class)) {
            return new CompositeDependencyStorage<>(getDependencyListSelf(targetClass, targetType));
        }

        if (WrapperTypes.isBeanCollection(containerType)) {
            List<?> beans = getDependencyListSelf(targetClass, targetType);
            return containerType.equals(Set.class) ? new LinkedHashSet<>(beans) : new ArrayList<>(beans);
        }

        if (containerType.equals(AtomicReference.class)) return new AtomicReference<>(resolvedInner);
        if (containerType.equals(WeakReference.class)) return new WeakReference<>(resolvedInner);
        if (containerType.equals(SoftReference.class)) return new SoftReference<>(resolvedInner);

        throw new IllegalStateException("Tipo de wrapper nao suportado: " + containerType.getName());
    }

    private <T> List<T> getDependencyListSelf(Class<T> reference, Type elementType) {
        List<Dependency> candidates = allCandidatesOf(reference);

        boolean filterByGenericArgument = genericResolutionEnabled.get()
                && elementType instanceof ParameterizedType
                && !GenericTypes.hasWildcard(elementType);

        return candidates.stream()
                .filter(dependency -> !filterByGenericArgument || matchesGenerically(elementType, dependency))
                .map(dependency -> castDependency(reference, dependency))
                .filter(Objects::nonNull)
                .toList();
    }

    private <T> T castDependency(Class<T> reference, Dependency dependency) {
        try{
            return reference.cast(dependency.getDependency());
        }catch (Exception e){
            log.error(
                    "Falha ao converter dependencia. reference={}, dependencyClass={}, msg={}",
                    reference.getName(),
                    describeDependencyClass(dependency),
                    e.getMessage(),
                    e
            );
            return null;
        }
    }

    private <T> AsyncComponent<T> getAsyncComponent(final Class<T> reference, final String qualifier, Supplier<Boolean> showWarnIfError){
        return getAsyncComponent(reference, reference, qualifier, showWarnIfError);
    }

    private List<Dependency> asyncCandidates(Class<?> reference, Type requested, String qualifier){
        List<Dependency> byQualifier = getDependencyMap(AsyncComponent.class)
                .values()
                .stream()
                .filter(d -> d.getQualifier().equals(qualifier))
                .toList();

        boolean matchByGenericArgument = genericResolutionEnabled.get()
                && requested instanceof ParameterizedType
                && !GenericTypes.hasWildcard(requested);

        if(matchByGenericArgument){
            List<Dependency> genericMatches = byQualifier.stream()
                    .filter(d -> matchesGenerically(requested, d))
                    .toList();

            if(!genericMatches.isEmpty()) return genericMatches;
        }

        return byQualifier.stream()
                .filter(d -> reference.equals(d.getDependencyClass()) || matchesGenerically(requested, d))
                .toList();
    }

    private <T> AsyncComponent<T> getAsyncComponent(
            final Class<T> reference,
            final Type requested,
            final String qualifier,
            Supplier<Boolean> showWarnIfError
    ){
        try{
            final List<Dependency> candidates = asyncCandidates(reference, requested, qualifier);

            if(candidates.isEmpty()){
                throw new DependencyInjectionException(
                        "Erro ao obter dependência: reference="+requested+", qualifier="+qualifier
                );
            }

            final Dependency dependencyObject = (candidates.size() == 1)
                    ? candidates.getFirst()
                    : applyAmbiguityPolicy(
                            requested,
                            qualifier,
                            candidates.stream().map(Dependency::getDependencyClass).filter(Objects::nonNull).toList(),
                            describeAsyncOrigin(requested),
                            candidates.getFirst()
                    );

            if(dependencyObject == null) return null;

            Object asyncComponentObject = dependencyObject.getDependency();
            if(asyncComponentObject instanceof AsyncComponent<?> asyncComponent){
                return (AsyncComponent<T>) asyncComponent;
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
        return extractType(field.getType(), field.getGenericType());
    }

    private ParamtrizedObject extractType(Parameter parameter){
        return extractType(parameter.getType(), parameter.getParameterizedType());
    }

    private ParamtrizedObject extractType(Class<?> rawType, Type genericType){
        if (genericType instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (WrapperTypes.isWrapper(rawType) && typeArgs.length == 1) {
                return new ParamtrizedObject(rawType, typeArgs[0], true, genericType);
            }
            return new ParamtrizedObject(rawType, genericType, false, genericType);
        }

        return new ParamtrizedObject(rawType, rawType, false, rawType);
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
                Object targetInstance = getObjectToInjectVariable(variable, paramtrizedObject, instance);
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

    private Object getObjectToInjectVariable(Field variable, ParamtrizedObject paramtrizedObject, Object instance) throws Exception{
        final Class<?> clazzVariable = paramtrizedObject.getBaseClass();
        final String qualifierName = getQualifierName(variable);

        if(getDependencyMap(clazzVariable).isEmpty() && childrenRegistration){
            try {
                registerDependency(clazzVariable);
            } catch (InvalidClassRegistrationException e) {
                throw new DependencyContainerRuntimeException(e);
            }
        }

        Dependency dependencyObject = findDependency(
                paramtrizedObject.getDeclaredType(),
                qualifierName,
                describeInjectionOrigin(variable, instance)
        );

        if(dependencyObject == null){
            throw new DependencyContainerException("Dependencia nao encontrada para: "+clazzVariable);
        }
        return dependencyObject.getDependency();
    }

    private static final String PRIMARY_QUALIFIER_PREFIX = "$primary$:";

    private Dependency resolveWithPrimary(Class<?> reference, Map<String, Dependency> map, String qualifier){
        if(map == null || map.isEmpty()) return null;
        if(isDefaultQualifier(qualifier)){
            Dependency primary = primaryDependencyIndex.get(reference);
            if(primary != null) return primary;
        }
        Dependency direct = map.get(qualifier);
        if(direct != null) return direct;
        return resolveSingleAsyncComponent(reference, map, qualifier);
    }

    private Dependency resolveSingleAsyncComponent(Class<?> reference, Map<String, Dependency> map, String qualifier){
        if(!AsyncComponent.class.equals(reference)) return null;

        List<Dependency> matches = map.values().stream()
                .filter(dependency -> qualifier.equals(dependency.getQualifier()))
                .toList();
        return (matches.size() == 1) ? matches.getFirst() : null;
    }

    private boolean isDefaultQualifier(String qualifier){
        return qualifier == null || qualifier.isEmpty() || "default".equalsIgnoreCase(qualifier);
    }

    private Dependency findDependency(Type requested, String qualifier, String origin){
        Class<?> reference = GenericTypes.raw(requested);
        if(reference == null) return null;

        Map<String, Dependency> map = getDependencyMap(reference);
        boolean defaultLookup = isDefaultQualifier(qualifier);

        if(!defaultLookup){
            Dependency explicit = map.get(qualifier);
            if(explicit != null){
                reportGenericMismatch(requested, explicit, qualifier, origin);
                return explicit;
            }
        }

        if(genericResolutionEnabled.get() && requested instanceof ParameterizedType){
            Dependency generic = resolveByGenericType(requested, qualifier, origin, map);
            if(generic != null) return generic;
        }

        if(map.isEmpty()) return null;

        if(defaultLookup){
            Dependency primary = primaryDependencyIndex.get(reference);
            if(primary != null) return primary;
        }

        Dependency direct = map.get(qualifier);
        if(direct != null){
            if(defaultLookup && isContestedSlot(reference, qualifier)){
                return applyAmbiguityPolicy(requested, qualifier, claimantsOf(reference, qualifier), origin, direct);
            }
            return direct;
        }

        return resolveSingleAsyncComponent(reference, map, qualifier);
    }

    private Dependency resolveByGenericType(Type requested, String qualifier, String origin, Map<String, Dependency> map){
        if(!GenericTypes.hasWildcard(requested)){
            Map<String, Dependency> slot = genericDependencyIndex.get(GenericTypes.key(requested));
            if(slot == null || slot.isEmpty()) return null;

            Dependency exact = slot.get(qualifier);
            if(exact != null) return exact;

            if(isDefaultQualifier(qualifier) && slot.size() == 1){
                return slot.values().iterator().next();
            }
            return null;
        }

        Class<?> reference = GenericTypes.raw(requested);

        List<Dependency> matches = genericCandidates(reference, qualifier, map).stream()
                .filter(dependency -> matchesGenerically(requested, dependency))
                .toList();

        if(matches.isEmpty()) return null;
        if(matches.size() == 1) return matches.getFirst();

        if(isDefaultQualifier(qualifier)){
            Dependency primary = primaryDependencyIndex.get(reference);
            if(primary != null && matches.contains(primary)) return primary;
        }

        List<Class<?>> candidates = matches.stream()
                .map(Dependency::getDependencyClass)
                .filter(Objects::nonNull)
                .toList();

        return applyAmbiguityPolicy(requested, qualifier, candidates, origin, matches.getFirst());
    }

    private List<Dependency> genericCandidates(Class<?> reference, String qualifier, Map<String, Dependency> rawRegistrations){
        if(reference == null) return List.of();

        Set<Dependency> candidates = Collections.newSetFromMap(new IdentityHashMap<>());

        genericIndexSlotsOf(reference)
                .map(slot -> slot.get(qualifier))
                .filter(Objects::nonNull)
                .forEach(candidates::add);

        Dependency rawCandidate = rawRegistrations.get(qualifier);
        if(rawCandidate != null) candidates.add(rawCandidate);

        return List.copyOf(candidates);
    }

    private Stream<Map<String, Dependency>> genericIndexSlotsOf(Class<?> reference){
        String prefix = reference.getName() + "<";
        return genericDependencyIndex.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue);
    }

    private List<Dependency> allCandidatesOf(Class<?> reference){
        if(reference == null) return List.of();

        Set<Dependency> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        candidates.addAll(getDependencyMap(reference).values());
        genericIndexSlotsOf(reference).flatMap(slot -> slot.values().stream()).forEach(candidates::add);

        return List.copyOf(candidates);
    }

    private boolean declaresGenericSupertype(Type requested, Class<?> serviceClass){
        return GenericTypes.supertypes(serviceClass).stream()
                .anyMatch(candidate -> GenericTypes.matches(requested, candidate));
    }

    private boolean matchesGenerically(Type requested, Dependency dependency){
        return dependency.getGenericSupertypes().stream()
                .anyMatch(candidate -> GenericTypes.matches(requested, candidate));
    }

    private void reportGenericMismatch(Type requested, Dependency resolved, String qualifier, String origin){
        if(!genericResolutionEnabled.get()) return;
        if(!(requested instanceof ParameterizedType)) return;
        if(GenericTypes.hasWildcard(requested)) return;

        Class<?> reference = GenericTypes.raw(requested);
        if(reference == null) return;

        boolean declaresSameRawGenerically = resolved.getGenericSupertypes().stream()
                .anyMatch(candidate -> reference.equals(GenericTypes.raw(candidate))
                        && GenericTypes.isFullyResolved(GenericTypes.key(candidate)));

        if(!declaresSameRawGenerically || matchesGenerically(requested, resolved)) return;

        String message = "Bean " + describeDependencyClass(resolved) + " selecionado pelo qualifier "
                + qualifier + " nao corresponde ao tipo generico " + requested.getTypeName()
                + ((origin != null && !origin.isBlank()) ? (". Origem: " + origin) : "");

        if(ambiguityPolicy.get() == AmbiguityPolicy.FAIL_FAST){
            throw new DependencyInjectionException(message);
        }

        log.warn(message);
    }

    private Dependency applyAmbiguityPolicy(
            Type requested,
            String qualifier,
            List<Class<?>> candidates,
            String origin,
            Dependency fallback
    ){
        AmbiguityPolicy policy = ambiguityPolicy.get();
        if(policy == AmbiguityPolicy.SILENT) return fallback;

        AmbiguousDependencyException error = new AmbiguousDependencyException(requested, qualifier, candidates, origin);
        if(policy == AmbiguityPolicy.FAIL_FAST) throw error;

        log.error(error.getMessage());
        return null;
    }

    private Object resolveDependency(Type requested, String qualifier, Supplier<Boolean> showWarnIfError, String origin){
        try{
            Dependency dependencyObject = findDependency(requested, qualifier, origin);
            if(dependencyObject == null){
                throw new DependencyInjectionException(
                        "Erro ao obter dependencia: reference=" + requested + ", qualifier=" + qualifier
                );
            }
            return dependencyObject.getDependency();
        }catch (AmbiguousDependencyException e){
            throw e;
        }catch (Exception e){
            Supplier<Boolean> warnSupplier = (showWarnIfError != null) ? showWarnIfError : () -> true;

            if(Boolean.TRUE.equals(warnSupplier.get())){
                log.error(
                        "Erro ao obter dependencia: reference={}, qualifier={}, origem={}, msg={}",
                        requested,
                        qualifier,
                        origin,
                        e.getMessage(),
                        e
                );
            }

            return null;
        }
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
            ServiceBean serviceBean = new ServiceBean(
                    beenClass,
                    0,
                    isAopEnabled(instance.getClass()),
                    (method != null) ? method.getGenericReturnType() : null
            );

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
            if(dependencyContainer.containsKey(clazz)) return;
            final Object toRegistrate = isAopEnabled(clazz) ? proxyObject(dependency, clazz) : dependency;
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
        registerObject(dependency, qualifier, aop, registration, null);
    }

    private void registerObject(
            @NonNull Object dependency,
            @NonNull String qualifier,
            boolean aop,
            ExternalComponentRegistration registration,
            Type declaredGenericType
    ) throws InvalidClassRegistrationException {
        try {
            final Class<?> clazz = dependency.getClass();
            if(!isProfileActive(clazz)) return;
            if(dependencyContainer.containsKey(clazz)) return;
            final Object toRegistrate = aop ? proxyObject(dependency, clazz) : dependency;
            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(clazz, qualifier);
            DependencyObject dependencyObject = new DependencyObject(clazz, qualifier, true, () -> {return toRegistrate;}, toRegistrate, declaredGenericType);
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
        registerObjectFunction(registrationFunction, isAop, registration, null);
    }

    private Type producedGenericType(Method method){
        Type returnType = method.getGenericReturnType();
        if(returnType instanceof ParameterizedType parameterized){
            Type[] arguments = parameterized.getActualTypeArguments();
            if(arguments.length == 1) return arguments[0];
        }
        return null;
    }

    private void registerObjectFunction(
            @NonNull RegistrationFunction<?> registrationFunction,
            Boolean isAop,
            ExternalComponentRegistration registration,
            Type declaredGenericType
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
            DependencyObject dependencyObject = new DependencyObject(referenceClass, qualifier, false, activatorFunction, activatorFunction, declaredGenericType);
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
        registerObjectFunction(asyncRegistrationFunction, isAop, null, null);
    }

    private void registerObjectFunction(
            @NonNull AsyncRegistrationFunction<?> asyncRegistrationFunction,
            Boolean isAop,
            ExternalComponentRegistration registration
    ){
        registerObjectFunction(asyncRegistrationFunction, isAop, registration, null);
    }

    private void registerObjectFunction(
            @NonNull AsyncRegistrationFunction<?> asyncRegistrationFunction,
            Boolean isAop,
            ExternalComponentRegistration registration,
            Type declaredGenericType
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

            String registrationKey = asyncRegistrationKey(referenceClass, qualifier, declaredGenericType);
            if(getDependencyMap(AsyncComponent.class).containsKey(registrationKey)){
                return;
            }

            final Map<String, Dependency> mapOfDependency = getDependencyMapAndValidDependency(
                    AsyncComponent.class,
                    registrationKey,
                    referenceClass
            );
            DependencyObject dependencyObject = new DependencyObject(
                    referenceClass, qualifier, false, activatorFunction, activatorFunction, declaredGenericType
            );

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
        trackAliasClaim(classToRegister, qualifier, dependencyObject);
        trackExternalSlot(registration, classToRegister, qualifier, dependencyObject);
        indexGenericTypes(classToRegister, dependencyObject, qualifier, registration);
        if(registerSubTypes)registerSubTypes(classToRegister, dependencyObject, qualifier, registration);
    }

    private void indexGenericTypes(
            Class<?> classToRegister,
            DependencyObject dependencyObject,
            String qualifier,
            ExternalComponentRegistration registration
    ){
        if(!genericResolutionEnabled.get()) return;
        if(AsyncComponent.class.equals(classToRegister)) return;

        for(String genericKey : dependencyObject.getGenericTypeKeys()){
            if(!GenericTypes.isFullyResolved(genericKey)) continue;

            Map<String, Dependency> slot = genericDependencyIndex.computeIfAbsent(
                    genericKey,
                    ignored -> new ConcurrentHashMap<>()
            );

            Dependency previous = slot.putIfAbsent(qualifier, dependencyObject);
            if(previous != null && previous != dependencyObject){
                log.warn(
                        "Mais de um bean registrado para o tipo generico {} com qualifier '{}': {} e {}.",
                        genericKey,
                        qualifier,
                        describeDependencyClass(previous),
                        describeDependencyClass(dependencyObject)
                );
                continue;
            }

            if(registration != null && previous == null){
                registration.addGenericSlot(new GenericRegistrationSlot(genericKey, qualifier, dependencyObject));
            }
        }
    }

    private String describeDependencyClass(Dependency dependency){
        Class<?> dependencyClass = (dependency != null) ? dependency.getDependencyClass() : null;
        return (dependencyClass != null) ? dependencyClass.getName() : "desconhecido";
    }

    private void removeGenericIndexEntries(Dependency dependencyObject){
        if(dependencyObject == null) return;

        for(String genericKey : dependencyObject.getGenericTypeKeys()){
            Map<String, Dependency> slot = genericDependencyIndex.get(genericKey);
            if(slot == null) continue;

            slot.values().removeIf(candidate -> candidate == dependencyObject);
            if(slot.isEmpty()){
                genericDependencyIndex.remove(genericKey, slot);
            }
        }
    }

    private void removeGenericRegistrationSlot(GenericRegistrationSlot slot){
        Map<String, Dependency> registrations = genericDependencyIndex.get(slot.genericKey());
        if(registrations == null) return;

        registrations.remove(slot.qualifier(), slot.dependency());

        if(registrations.isEmpty()){
            genericDependencyIndex.remove(slot.genericKey(), registrations);
        }
    }

    private void removeAliasClaims(Dependency dependencyObject){
        Class<?> claimant = (dependencyObject != null) ? dependencyObject.getDependencyClass() : null;
        if(claimant == null) return;

        contestedAliases.values().forEach(claimants -> claimants.remove(claimant));
        contestedAliases.values().removeIf(Set::isEmpty);
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
        Map<String, Dependency> mapOfDependency = dependencyContainer.get(referenceClass);
        return (mapOfDependency != null) ? mapOfDependency : Map.of();
    }

    private String asyncRegistrationKey(Class<?> referenceClass, String qualifier){
        return asyncRegistrationKey(referenceClass, qualifier, null);
    }

    private String asyncRegistrationKey(Class<?> referenceClass, String qualifier, Type declaredGenericType){
        if(declaredGenericType != null && genericResolutionEnabled.get()){
            String genericKey = GenericTypes.key(declaredGenericType);
            if(GenericTypes.isFullyResolved(genericKey)){
                return qualifier + "|" + genericKey;
            }
        }
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

        Set<Class<?>> visited = new LinkedHashSet<>();

        for(Class<?> interfaceObj : clazz.getInterfaces()){
            registerInterfaceAliases(interfaceObj, dependencyObject, qualifier, registration, visited);
        }

        if(clazz.isAnnotationPresent(ExcludeRootRegistration.class)){
            return;
        }

        Class<?> superClass = clazz.getSuperclass();
        while(superClass != null && !superClass.equals(Object.class) && !superClass.isInterface()){
            if(!visited.add(superClass)) break;

            registerAlias(superClass, dependencyObject, qualifier, registration);

            for(Class<?> interfaceObj : superClass.getInterfaces()){
                registerInterfaceAliases(interfaceObj, dependencyObject, qualifier, registration, visited);
            }

            superClass = superClass.getSuperclass();
        }
    }

    private void registerInterfaceAliases(
            Class<?> interfaceObj,
            @NonNull DependencyObject dependencyObject,
            @NonNull String qualifier,
            ExternalComponentRegistration registration,
            Set<Class<?>> visited
    ){
        if(interfaceObj == null || interfaceObj.equals(Object.class) || !visited.add(interfaceObj)){
            return;
        }

        registerAlias(interfaceObj, dependencyObject, qualifier, registration);

        for(Class<?> parent : interfaceObj.getInterfaces()){
            registerInterfaceAliases(parent, dependencyObject, qualifier, registration, visited);
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

        trackAliasClaim(indexedType, qualifier, dependencyObject);

        if(registration == null){
            registrations.put(qualifier, dependencyObject);
            return;
        }

        Dependency previous = registrations.putIfAbsent(qualifier, dependencyObject);
        if(previous == null){
            trackExternalSlot(registration, indexedType, qualifier, dependencyObject);
        }
    }

    private void trackAliasClaim(Class<?> indexedType, String qualifier, Dependency dependencyObject){
        Class<?> claimant = dependencyObject.getDependencyClass();
        if(claimant == null) return;

        Set<Class<?>> claimants = contestedAliases.computeIfAbsent(
                new AliasSlot(indexedType, qualifier),
                ignored -> ConcurrentHashMap.newKeySet()
        );

        if(claimants.add(claimant) && claimants.size() == 2){
            log.warn(
                    "Mais de um bean registrado para {} com qualifier '{}': {}. Use @Qualifier, @Primary ou um tipo generico mais especifico.",
                    indexedType.getName(),
                    qualifier,
                    claimants.stream().map(Class::getName).sorted().collect(Collectors.joining(", "))
            );
        }
    }

    private boolean isContestedSlot(Class<?> indexedType, String qualifier){
        Set<Class<?>> claimants = contestedAliases.get(new AliasSlot(indexedType, qualifier));
        return claimants != null && claimants.size() > 1;
    }

    private List<Class<?>> claimantsOf(Class<?> indexedType, String qualifier){
        Set<Class<?>> claimants = contestedAliases.get(new AliasSlot(indexedType, qualifier));
        return (claimants != null) ? List.copyOf(claimants) : List.of();
    }

    private record AliasSlot(Class<?> indexedType, String qualifier) {}

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
        if(mainClass != null){
            loadedSystemClasses.addAll(classFinder.find(mainClass, classFinderConfigurations));
        }else{
            loadedSystemClasses.addAll(classFinder.find(classFinderConfigurations));
        }
    }

    private void injectExternalModules(){
        Set<Class<?>> discoveredClasses = ConcurrentHashMap.newKeySet();

        forEachClass(loadedSystemClasses, CLASS_SCAN_PARALLEL_THRESHOLD, clazz -> {
            if(AnnotationsUtils.getMetaAnnotation(clazz, Import.class) == null) return;
            scanRecursive(clazz, new HashSet<>(), discoveredClasses);
        });

        loadedSystemClasses.addAll(discoveredClasses);
    }

    private void scanRecursive(Class<?> clazz, Set<Class<?>> visited, Set<Class<?>> globalResult){
        if(!visited.add(clazz)) return;

        Import importAnnotation = AnnotationsUtils.getMetaAnnotation(clazz, Import.class);

        if(importAnnotation != null){
            Class<?>[] configs = importAnnotation.value();

            for (Class<?> toImport : configs) {
                globalResult.add(toImport);
                scanRecursive(toImport, visited, globalResult);
            }

        }

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
            for (Field variable : listOfRegistration) {
                CompletableFuture<?> task = CompletableFuture.runAsync(() -> {
                    injectVariable(variable, instance);
                }, mainVirtualExecutor);
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
            Object instance = resolveDependency(reference, qualifier, showWarnIfError, origin);
            return (instance != null) ? reference.cast(instance) : null;
        }catch (AmbiguousDependencyException e){
            throw e;
        }catch (Exception e){
            Supplier<Boolean> warnSupplier = (showWarnIfError != null) ? showWarnIfError : () -> true;

            if(Boolean.TRUE.equals(warnSupplier.get())){
                log.error("Erro ao obter dependencia: reference={}, qualifier={}, origem={}, msg={}", reference.getName(), qualifier, origin, e.getMessage(), e);
            }

            return null;
        }
    }

    private String describeAsyncOrigin(Type requested){
        return "AsyncComponent<" + ((requested != null) ? requested.getTypeName() : "?") + ">";
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

        List<Field> injectFields = ReflectionCache.fieldsWithAnnotation(clazz, Inject.class);
        List<Field> valueFields = ReflectionCache.fieldsWithAnnotation(clazz, Value.class);

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
        return resolveValue(value.key(), value.defaultValue(), type, genericType, settings);
    }

    private Object resolveValue(String key, String def, Class<?> type, Type genericType, AppSettings settings) {
        if(type == Optional.class){
            return resolveOptionalValue(key, def, genericType, settings);
        }

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

        if (ContainerDefaults.isContainer(type)) {
            return resolveContainerValue(key, type, genericType, settings);
        }

        return settings.getObject(key, type);
    }

    private Object resolveOptionalValue(String key, String def, Type genericType, AppSettings settings) {
        Type innerType = ContainerDefaults.firstTypeArgument(genericType);
        Class<?> innerRaw = ContainerDefaults.rawClass(innerType);
        if(innerRaw == null) innerRaw = Object.class;

        boolean hasDefault = def != null && !def.isEmpty();
        if(!settings.has(key) && !hasDefault){
            return Optional.empty();
        }

        return Optional.ofNullable(resolveValue(key, def, innerRaw, innerType, settings));
    }

    private Object resolveContainerValue(String key, Class<?> type, Type genericType, AppSettings settings) {
        if(settings.has(key)){
            Object resolved = (genericType instanceof ParameterizedType || type.isArray())
                    ? settings.getObject(key, genericType)
                    : settings.getObject(key, type);
            if(resolved != null && type.isInstance(resolved)) return resolved;
        }
        return ContainerDefaults.newEmpty(type);
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
