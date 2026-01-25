package dtm.di.storage.containers;

import dtm.di.annotations.*;
import dtm.di.annotations.aop.Aspect;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.core.ClassFinderDependencyContainer;
import dtm.di.core.DependencyContainer;
import dtm.di.core.InjectionStrategy;
import dtm.di.exceptions.*;
import dtm.di.prototypes.CompositeDependency;
import dtm.di.prototypes.Dependency;
import dtm.di.prototypes.LazyDependency;
import dtm.di.prototypes.RegistrationFunction;
import dtm.di.prototypes.proxy.ProxyFactory;
import dtm.di.sort.TopologicalSorter;
import dtm.di.storage.*;
import dtm.di.storage.bean.BeanDependencyGraphBuilder;
import dtm.di.storage.bean.BeanGraph;
import dtm.di.storage.composite.CompositeDependencyStorage;
import dtm.di.storage.lazy.Lazy;
import dtm.di.storage.lazy.ParamtrizedObject;
import dtm.discovery.core.ClassFinder;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.finder.ClassFinderService;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dtm.di.common.AnotationsUtils.hasMetaAnnotation;
import static dtm.di.common.AnotationsUtils.getAllFieldWithAnnotation;

@DisableAop
@Slf4j
@SuppressWarnings("unchecked")
public class DependencyContainerStorage implements DependencyContainer, ClassFinderDependencyContainer {

    private final ExecutorService mainExecutor;
    private final ExecutorService mainVirtualExecutor;

    private final AtomicReference<InjectionStrategy> injectionStrategy;

    private final Map<Class<?>, List<Dependency>> dependencyContainer;
    private final ClassFinder classFinder;
    private final AtomicBoolean loaded;

    private final List<String> foldersToLoad;

    private final List<ServiceBean> serviceBeensDefinition;
    private final List<Set<ServiceBean>> serviceBeensDefinitionLayer;

    private final Set<Class<?>> loadedSystemClasses;

    private final Map<Class<?>, List<Method>> externalBeenBefore;
    private final Map<Class<?>, List<Method>> externalBeenAfter;

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


    private DependencyContainerStorage(Class<?> mainClass, String... profiles){
        ThreadFactory vFactory = Thread.ofVirtual()
                .name("MainVirtual-", 0)
                .factory();

        this.mainExecutor = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors()),
                runnable -> {
                    Thread t = new Thread(runnable);
                    t.setName("MainExecutor-Worker-" + t.hashCode());
                    t.setDaemon(true);
                    return t;
                }
        );
        this.mainVirtualExecutor = Executors.newThreadPerTaskExecutor(vFactory);
        this.dependencyContainer = new ConcurrentHashMap<>();
        this.loaded = new AtomicBoolean(false);
        this.classFinder = new ClassFinderService();
        this.childrenRegistration = false;
        this.injectionStrategy = new AtomicReference<>(InjectionStrategy.ADAPTIVE);
        this.foldersToLoad = new ArrayList<>();
        this.serviceBeensDefinition = Collections.synchronizedList(new ArrayList<>());
        this.loadedSystemClasses = ConcurrentHashMap.newKeySet();
        this.serviceBeensDefinitionLayer = Collections.synchronizedList(new ArrayList<>());
        this.externalBeenBefore = new LinkedHashMap<>();
        this.externalBeenAfter = new LinkedHashMap<>();
        this.classFinderConfigurations = getFindConfigurations();
        this.mainClass = mainClass;
        if(profiles.length > 0){
            this.profiles = Arrays.stream(profiles)
                    .filter((profile) -> profile != null && !profile.isEmpty())
                    .toList();

        }else{
            this.profiles = List.of("default");
        }
    }

    @Override
    public void load() throws InvalidClassRegistrationException {
        try{
            if(isLoaded()) return;
            loadByPluginFolder();
            loadSystemClasses();
            filterServiceClass();
            filterExternalsBeens();
            loaded.set(true);
            selfInjection();
            registerExternalBeens(externalBeenBefore);
            loadBeens();
            registerExternalBeens(externalBeenAfter);
        }catch (Exception e){
           throw new UnloadError("load error", e);
        }
    }

    @Override
    public void unload() {
        loaded.set(false);
        this.classFinderConfigurations = getFindConfigurations();
        loadedSystemClasses.clear();
        serviceBeensDefinition.clear();
        dependencyContainer.clear();
        foldersToLoad.clear();
        externalBeenBefore.clear();
        externalBeenAfter.clear();
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
    public void setInjectionStrategy(InjectionStrategy injectionStrategy) {
        this.injectionStrategy.set(injectionStrategy != null ? injectionStrategy : InjectionStrategy.ADAPTIVE);
    }

    @Override
    public <T> T getDependency(Class<T> reference) {
        return getDependency(reference, getQualifierName(reference));
    }

    @Override
    public <T> T getDependency(Class<T> reference, String qualifier) {
        throwIfUnload();
        try{
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(reference, Collections.emptyList());
            final Dependency dependencyObject = listOfDependency.stream().filter(d -> d.getQualifier().equals(qualifier)).findFirst().orElseThrow(() -> {
                return new DependencyInjectionException("Erro ao obter dependência: reference="+reference+", qualifier="+qualifier);
            });
            Object instance = dependencyObject.getDependency();
            return reference.cast(instance);
        }catch (Exception e){
            log.error("Erro ao obter dependência: reference={}, qualifier={}, msg={}", reference.getName(), qualifier, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public <T> List<T> getDependencyList(Class<T> reference) {
        throwIfUnload();
        try{
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(reference, Collections.emptyList());
            return listOfDependency.stream().map(d -> {
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

    @Override
    public <T, S extends T> Map<Class<S>, S> getInstancesByClass(Class<T> assignableClass) {
        Map<Class<S>, S> classSMap = new ConcurrentHashMap<>();

        for(Map.Entry<Class<?>, List<Dependency>> entry : dependencyContainer.entrySet()){
            final Class<?> refClass = entry.getKey();
            final List<Dependency> dependencyList = entry.getValue();

            if (assignableClass.isAssignableFrom(refClass)) {
                for (Dependency dependency : dependencyList) {
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
            return (T)createObject(referenceClass, isAop(referenceClass));
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    @Override
    public <T> T newInstance(Class<T> referenceClass, Object... contructorArgs) throws NewInstanceException {
        throwIfUnload();
        try{
            return (T)createObject(referenceClass, isAop(referenceClass), contructorArgs);
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    @Override
    public void injectDependencies(Object instance) {
        throwIfUnload();
        if(instance == null) return;

        final Class<?> clazz = instance.getClass();

        List<Field> listOfRegistration = getAllFieldWithAnnotation(clazz, Inject.class);

        if(isParallelInjection(listOfRegistration.size())){
            injectDependenciesParallel(instance, listOfRegistration);
        }else{
            injectDependenciesSequential(instance, listOfRegistration);
        }
    }

    @Override
    public List<Dependency> getRegisteredDependencies() {
        return new ArrayList<>(dependencyContainer.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(
                        Dependency::getDependencyClass,
                        d -> d,
                        (existing, replacement) -> existing
                ))
                .values());
    }

    @Override
    public Set<Class<?>> getLoadedSystemClasses() {
        return loadedSystemClasses;
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
    public <T> void registerDependency(RegistrationFunction<T> registrationFunction) throws InvalidClassRegistrationException {
        registerObject(registrationFunction);
    }

    @Override
    public void unRegisterDependency(Class<?> dependency) {
        throwIfUnload();
        if(!dependencyContainer.containsKey(dependency)) return;
        List<Dependency> dependencyList = new ArrayList<>(dependencyContainer.get(dependency));

        for (Dependency dependencyObj : dependencyList){
            for(Class<?> clazz : dependencyObj.getDependencyClassInstanceTypes()){
                dependencyContainer.remove(clazz);
            }
        }
    }

    private void throwIfUnload(){
        if(!isLoaded()) throw new UnloadError("unload: DependencyContainer");
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
            loadBeensInlayer(layer);
        }
    }

    private void loadBeensInlayer(Set<ServiceBean> layer) throws InvalidClassRegistrationException{
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        for (ServiceBean serviceBean : layer) {
            CompletableFuture<?> task = CompletableFuture.runAsync(() -> {
                try {
                    loadBeen(serviceBean, new HashSet<>(), getQualifierName(serviceBean.getClazz()));
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
            loadBeen(service, new HashSet<>(), getQualifierName(service.getClazz()));
        }
    }

    private void loadBeen(ServiceBean been, final Set<Class<?>> registeringClasses, String qualifier) throws InvalidClassRegistrationException{
        final Class<?> dependency = been.getClazz();

        try {
            if (dependencyContainer.containsKey(dependency)) return;
            validRegistration(dependency, registeringClasses);
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(dependency, new ArrayList<Dependency>());
            validQualifier(listOfDependency, qualifier, dependency);
            if(childrenRegistration){
                registerAutoInject(dependency, registeringClasses);
            }

            boolean singleton = isSingleton(dependency);
            DependencyObject dependencyObject = singleton
                   ? DependencyObject.builder()
                            .dependencyClass(dependency)
                            .qualifier(qualifier)
                            .singleton(true)
                            .creatorFunction(null)
                            .singletonInstance(createObject(dependency, been.isAop()))
                        .build()
                   : DependencyObject.builder()
                            .dependencyClass(dependency)
                            .qualifier(qualifier)
                            .singleton(false)
                            .creatorFunction(createActivationFunction(dependency, been.isAop()))
                            .singletonInstance(null)
                        .build();


            listOfDependency.add(dependencyObject);
            dependencyContainer.put(dependency, listOfDependency);
            registerSubTypes(dependency, listOfDependency);
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
        List<Class<?>> listOfRegistration = Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> {
                    Class<?> fieldClass = f.getType();
                    return f.isAnnotationPresent(Inject.class) && !(
                            fieldClass.isInterface() ||
                                    !fieldClass.isEnum() ||
                                    fieldClass.isAnnotation() ||
                                    Modifier.isAbstract(fieldClass.getModifiers())
                    );
                })
                .map(Field::getType)
                .collect(Collectors.toList());

        int order = 0;
        for(Class<?> subClass : listOfRegistration){
            if(!dependencyContainer.containsKey(subClass)){
                loadBeen(new ServiceBean(subClass, order++, isAop(clazz)), registeringClasses, getQualifierName(subClass));
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

    private void validQualifier(final List<Dependency> listOfDependency, String qualifier, Class<?> dependency) throws InvalidClassRegistrationException{
        final boolean containsQualifier = listOfDependency.stream().anyMatch(d -> d.getQualifier().equals(qualifier));
        if(containsQualifier){
            throw new InvalidClassRegistrationException("Qualificador '"+qualifier+"' ja registrado para a dependencia: "+dependency, dependency);
        }
    }

    private void filterServiceClass(){
        final Set<Class<?>> serviceLoadedClassActive = getConcreteServiceLoadedClass(Component.class);

        final int total = serviceLoadedClassActive.size();

        final Map<Class<?>, Set<Class<?>>> dependencyGraph = new ConcurrentHashMap<>();

        if (total < thresholdConcurent) {
            processDependencyServiceWithParallelStream(dependencyGraph, serviceLoadedClassActive);
        } else {
            processDependencyServiceWithExecutorService(dependencyGraph, serviceLoadedClassActive);
        }

        if(processInlayer){
            List<Set<Class<?>>> classLayers = groupByDependencyLayer(serviceLoadedClassActive, dependencyGraph);

            int order = 0;
            for (Set<Class<?>> classSet : classLayers) {
                int layerOrder = order;
                Set<ServiceBean> layer = ConcurrentHashMap.newKeySet();

                classSet.parallelStream().forEach(clazz -> {
                    layer.add(new ServiceBean(clazz, layerOrder, isAop(clazz)));
                });

                serviceBeensDefinitionLayer.add(layer);
                order++;
            }
        }else{
            Set<Class<?>> ordered = TopologicalSorter.sort(serviceLoadedClassActive, dependencyGraph);
            int order = 0;
            for (Class<?> clazz : ordered) {
                serviceBeensDefinition.add(new ServiceBean(clazz, order++, isAop(clazz)));
            }
        }
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

    private String getQualifierName(@NonNull Method beenMethod){
        if(beenMethod.isAnnotationPresent(Service.class)){
            Service qualifierAnnotation = beenMethod.getAnnotation(Service.class);
            return (qualifierAnnotation.qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? "default" : qualifierAnnotation.qualifier();
        }else if(beenMethod.isAnnotationPresent(Component.class)){
            Component qualifierAnnotation = beenMethod.getAnnotation(Component.class);
            return (qualifierAnnotation.qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? "default" : qualifierAnnotation.qualifier();
        } else if(beenMethod.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = beenMethod.getAnnotation(Qualifier.class);
            return (qualifierAnnotation.value() == null || qualifierAnnotation.value().isEmpty()) ? "default" : qualifierAnnotation.value();
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

        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Inject.class)) {
                Class<?> fieldType = field.getType();
                dependencies.addAll(isServiceDependency(fieldType, serviceLoadedClass, field));
            }
        }

        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            for (Parameter param : constructor.getParameters()) {
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
        BeanDependencyGraphBuilder builder = new BeanDependencyGraphBuilder(serviceClasses);
        BeanGraph beanGraph = builder.buildGraph(configClasses);

        Map<Class<?>, List<Method>> beforeBeans = beanGraph.getBeforeServiceBeans(serviceClasses);

        Map<Class<?>, List<Method>> afterBeans = beanGraph.getAfterServiceBeans(serviceClasses);

        this.externalBeenBefore.clear();
        this.externalBeenBefore.putAll(beforeBeans);

        this.externalBeenAfter.clear();
        this.externalBeenAfter.putAll(afterBeans);
    }



    private void selfInjection() throws InvalidClassRegistrationException{
        registerObject(this);
    }

    private void registerExternalBeens(Map<Class<?>, List<Method>> configurationsClasses) throws InvalidClassRegistrationException{
        for(Map.Entry<Class<?>, List<Method>> configurationsClass : configurationsClasses.entrySet()){
            final Class<?> clazz = configurationsClass.getKey();
            List<Method> methodsList = configurationsClass.getValue();

            if(!methodsList.isEmpty()){
                registerExternalBeen(clazz, methodsList, true);
            }
        }
    }


    private void registerExternalBeen(Class<?> configurationsClass, List<Method> methodsList, boolean load) throws InvalidClassRegistrationException{
        try {
            Object configurationInstance = newInstance(configurationsClass, false);
            for (Method method : methodsList) {
                Object result = null;
                Class<?>[] parameterTypes = method.getParameterTypes();
                Object[] args = new Object[parameterTypes.length];

                if(load){
                    for(int i = 0; i < parameterTypes.length; i++){
                        final Class<?> clazz = parameterTypes[i];
                        try{
                            args[i] = getDependency(clazz);
                        }catch (Exception e){
                            args[i] = null;
                        }
                    }
                }else{
                    Arrays.fill(args, null);
                }


                method.setAccessible(true);


                result = method.invoke(configurationInstance, args);

                if(result != null){
                    String qualifier = getQualifierName(method);
                    boolean singleton = isSingletonBeen(method);
                    if(singleton){
                        boolean aop = (isAop(method) && isAop(result.getClass()));
                        registerObject(result, qualifier, aop);
                    }else {
                        registerExternalBeenNoSinglenton(result, method, qualifier);
                    }
                }
            }
        } catch (Exception e) {
            throw new InvalidClassRegistrationException("Erro ao configurar: "+configurationsClass, configurationsClass, e);
        }
    }

    private Object createObject(@NonNull Class<?> clazz){
        return createObject(clazz, isAop(clazz));
    }

    private Object createObject(@NonNull Class<?> clazz, boolean aop){
        try {
            Object instance = null;
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                if (constructor.getParameterCount() == 0) {
                    instance = createWithOutConstructor(clazz);
                    break;
                }
            }
            instance = (instance == null) ? createWithConstructor(clazz, constructors) : instance;
            injectDependencies(Objects.requireNonNull(instance));
            Object object =  (aop) ? proxyObject(instance, clazz) : instance;
            executePostCreationMethod(clazz, object);
            return object;
        }catch (Exception e) {
            log.error("Erro ao criar instância para a classe: {}", clazz.getName(), e);
            String message = "Erro ao criar instância "+clazz+" ==> cause: "+e.getMessage();
            throw new NewInstanceException(message, clazz);
        }
    }

    private Object createObject(@NonNull Class<?> clazz, boolean aop, Object[] extraConstructorArgs){
        try {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            List<Parameter> failedParams = new ArrayList<>();
            for (Constructor<?> constructor : constructors) {
                Parameter[] parameterTypes = constructor.getParameters();
                Object[] resolvedArgs = tryResolveConstructorArgs(parameterTypes, extraConstructorArgs, failedParams);

                if (resolvedArgs != null) {
                    constructor.setAccessible(true);
                    Object instance = constructor.newInstance(resolvedArgs);
                    injectDependencies(instance);
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
                    .map(this::getDependecyObjectByParam)
                    .toArray();

            return chosenConstructor.newInstance(args);
        }catch (Exception e){
            log.error("Falha ao criar instância de {} com construtor. Tentando fallback sem construtor. Erro: {}", clazz.getName(), e.getMessage(), e);
            try{
                return createWithOutConstructor(clazz);
            }catch (Exception ex){
                log.error("Falha ao criar instância de {} até mesmo via fallback. Erro: {}", clazz.getName(), ex.getMessage(), ex);
                return null;
            }
        }
    }

    private Object getDependecyObjectByParam(Parameter parameter){
        final ParamtrizedObject paramtrizedObject = extractType(parameter);

        if(paramtrizedObject.isParametrized()){
            final Class<?> clazzVariable = paramtrizedObject.getParamClass();

            if(paramtrizedObject.getBaseClass().equals(LazyDependency.class)){
                return Lazy.of(() -> getDependency(clazzVariable));
            }else if(paramtrizedObject.getBaseClass().equals(CompositeDependency.class)){
                return new CompositeDependencyStorage<>(getDependencyList(clazzVariable));
            }else if(paramtrizedObject.getBaseClass().equals(AtomicReference.class)){
                return new AtomicReference<>(getDependencyList(clazzVariable));
            }else if(paramtrizedObject.getBaseClass().equals(WeakReference.class)){
                return new WeakReference<>(getDependencyList(clazzVariable));
            }else if(paramtrizedObject.getBaseClass().equals(SoftReference.class)){
                return new SoftReference<>(getDependencyList(clazzVariable));
            } else {
                return null;
            }
        }else{
            return getDependency(paramtrizedObject.getBaseClass());
        }
    }

    private ParamtrizedObject extractType(Field field){
        Class<?> fieldType = field.getType();
        Type genericType = field.getGenericType();

        if (genericType instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
                return new ParamtrizedObject(fieldType, (Class<?>) typeArgs[0], true);
            }
        }

        return new ParamtrizedObject(fieldType, fieldType, false);
    }

    private ParamtrizedObject extractType(Parameter parameter){
        Class<?> fieldType = parameter.getType();
        Type genericType = parameter.getParameterizedType();

        if (genericType instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
                return new ParamtrizedObject(fieldType, (Class<?>) typeArgs[0], true);
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

            
            if(paramtrizedObject.isParametrized()){
                final Class<?> clazzVariable = paramtrizedObject.getParamClass();

                if(paramtrizedObject.getBaseClass().equals(LazyDependency.class)){
                    Supplier<Object> getDependecyLazy = () -> {
                        try{
                            return getObjectToInjectVariable(variable, clazzVariable, instance);
                        }catch (Exception e){
                            String instanceClassName = (instance != null) ? instance.getClass().getName() : "[instancia nula]";
                            log.error("Erro ao carregar LAZY da variável {} na classe {}. Causa: {}", variable.getName(), instanceClassName, e.getMessage(), e);
                            return null;
                        }
                    };
                    variable.set(instance, Lazy.of(getDependecyLazy));
                }else if(paramtrizedObject.getBaseClass().equals(CompositeDependency.class)){
                    List<?> dependencyList = getObjectListToInjectVariable(clazzVariable);
                    variable.set(instance, new CompositeDependencyStorage<>(dependencyList));
                }else if(paramtrizedObject.getBaseClass().equals(AtomicReference.class)){
                    variable.set(instance, new AtomicReference<>(getDependencyList(clazzVariable)));
                }else if(paramtrizedObject.getBaseClass().equals(WeakReference.class)){
                    variable.set(instance, new WeakReference<>(getDependencyList(clazzVariable)));
                }else if(paramtrizedObject.getBaseClass().equals(SoftReference.class)){
                    variable.set(instance, new SoftReference<>(getDependencyList(clazzVariable)));
                }

            }else{
                Object targetInstance = getObjectToInjectVariable(variable, paramtrizedObject.getBaseClass(), instance);
                variable.set(instance, targetInstance);
            }

        }catch (Exception e){
            String instanceClassName = (instance != null) ? instance.getClass().getName() : "[instancia nula]";
            log.error("Erro ao injetar variável '{}' na classe '{}'. Causa: {}",
                    variable.getName(),
                    instanceClassName,
                    e.getMessage(),
                    e
            );
        }
    }

    private Object getObjectToInjectVariable(Field variable, Class<?> clazzVariable, Object instance) throws Exception{
        final String qualifierName = getQualifierName(variable);
        List<Dependency> listOfDependency = dependencyContainer.getOrDefault(clazzVariable, Collections.emptyList());
        if(listOfDependency.isEmpty() && childrenRegistration){
            try {
                registerDependency(clazzVariable);
            } catch (InvalidClassRegistrationException e) {
                throw new DependencyContainerRuntimeException(e);
            }
            listOfDependency = dependencyContainer.getOrDefault(clazzVariable, Collections.emptyList());
        }
        Dependency dependencyObject = listOfDependency.stream().filter(d -> d.getQualifier().equals(qualifierName)).findFirst().orElseThrow(() -> new DependencyContainerException("Dependencia não encontrada para: "+clazzVariable));
        return dependencyObject.getDependency();
    }

    private <T> List<T> getObjectListToInjectVariable(Class<T> clazzVariable) throws Exception{
        List<Dependency> listOfDependency = dependencyContainer.getOrDefault(clazzVariable, Collections.emptyList());
        if(listOfDependency.isEmpty() && childrenRegistration){
            try {
                registerDependency(clazzVariable);
            } catch (InvalidClassRegistrationException e) {
                throw new DependencyContainerRuntimeException(e);
            }
            listOfDependency = dependencyContainer.getOrDefault(clazzVariable, Collections.emptyList());
        }
        return listOfDependency.stream().map(d -> {
                    try{
                        return clazzVariable.cast(d.getDependency());
                    } catch (Exception e) {
                        log.error(
                                "Falha ao converter dependência. Esperado: {}, Obtido: {}, Erro: {}",
                                clazzVariable.getName(),
                                (d.getDependency() != null ? d.getDependency().getClass().getName() : "null"),
                                e.getMessage(),
                                e
                        );
                        return null;
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private void registerExternalBeenNoSinglenton(@NonNull Object instance, Method method, String qualifier) throws InvalidClassRegistrationException{
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
            ServiceBean serviceBean = new ServiceBean(beenClass, 0, isAop(instance.getClass()));

            loadBeen(serviceBean, new HashSet<>(), qualifier);
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

    private void registerObject(@NonNull Object dependency, @NonNull String qualifier) throws InvalidClassRegistrationException {
        try {
            final Class<?> clazz = dependency.getClass();
            final Object toRegistrate = isAop(clazz) ? proxyObject(dependency, clazz) : dependency;
            if(dependencyContainer.containsKey(clazz)) return;
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(clazz, new ArrayList<Dependency>());
            validQualifier(listOfDependency, qualifier, clazz);
            DependencyObject dependencyObject = new DependencyObject(clazz, qualifier, true, () -> {return toRegistrate;}, toRegistrate);
            listOfDependency.add(dependencyObject);
            dependencyContainer.put(clazz, listOfDependency);
            registerSubTypes(clazz, listOfDependency);
        }catch (Exception e) {
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + dependency.getClass()+ " ==> causa: "+e.getMessage(),
                    dependency.getClass(),
                    e
            );
        }
    }

    private void registerObject(@NonNull Object dependency, @NonNull String qualifier, boolean aop) throws InvalidClassRegistrationException {
        try {
            final Class<?> clazz = dependency.getClass();
            final Object toRegistrate = aop ? proxyObject(dependency, clazz) : dependency;
            if(dependencyContainer.containsKey(clazz)) return;
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(clazz, new ArrayList<Dependency>());
            validQualifier(listOfDependency, qualifier, clazz);
            DependencyObject dependencyObject = new DependencyObject(clazz, qualifier, true, () -> {return toRegistrate;}, toRegistrate);
            listOfDependency.add(dependencyObject);
            dependencyContainer.put(clazz, listOfDependency);
            registerSubTypes(clazz, listOfDependency);
        }catch (Exception e) {
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + dependency.getClass()+ " ==> causa: "+e.getMessage(),
                    dependency.getClass(),
                    e
            );
        }
    }

    private void registerObject(@NonNull RegistrationFunction<?> registrationFunction){
        final Class<?> referenceClass = registrationFunction.getReferenceClass();
        final String qualifier = (registrationFunction.getQualifier().isEmpty()) ? "default" : registrationFunction.getQualifier();

        try{
            Supplier<?> activatorFunction;
            if(isAop(registrationFunction.getReferenceClass())){
                activatorFunction = () -> {
                    Object instance = registrationFunction.getFunction().get();
                    if(instance == null) throw new NullPointerException("Intancia invalida para "+referenceClass);
                    return proxyObject(instance, instance.getClass());
                };
            }else{
                activatorFunction = registrationFunction.getFunction();
            }
            if(dependencyContainer.containsKey(referenceClass)) return;
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(referenceClass, new ArrayList<Dependency>());
            validQualifier(listOfDependency, qualifier, referenceClass);
            DependencyObject dependencyObject = new DependencyObject(referenceClass, qualifier, false, activatorFunction, activatorFunction);
            listOfDependency.add(dependencyObject);
            dependencyContainer.put(referenceClass, listOfDependency);
            registerSubTypes(referenceClass, listOfDependency);
        }catch (Exception e) {
            throw new InvalidClassRegistrationException(
                    "Erro ao criar a dependencia: " + referenceClass+ " ==> causa: "+e.getMessage(),
                    referenceClass,
                    e
            );
        }
    }

    private void registerSubTypes(@NonNull Class<?> clazz, @NonNull List<Dependency> listOfDependency){
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
            dependencyContainer.put(superClass, listOfDependency);
        }

        for(Class<?> interfaceObj : interfaces){
            if (!interfaceObj.equals(Object.class)) {
                dependencyContainer.put(interfaceObj, listOfDependency);
            }
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
        if(mainClass != null){
            loadedSystemClasses.addAll(classFinder.find(mainClass, classFinderConfigurations));
        }else{
            loadedSystemClasses.addAll(classFinder.find(classFinderConfigurations));
        }
    }

    private boolean filterConcreteBean(Class<?> clazz, Class<? extends Annotation> annotation){
        return hasMetaAnnotation(clazz, annotation) && isConcreteClass(clazz);
    }

    private boolean filterConcreteBeanAndActive(Class<?> clazz, Class<? extends Annotation> annotation){
        if (!isConcreteClass(clazz) || !hasMetaAnnotation(clazz, annotation)) {
            return false;
        }

        Profile profile = clazz.getAnnotation(Profile.class);

        if (profile != null) {
            String selectedProfile = profile.value();
            return selectedProfile == null || selectedProfile.isEmpty() || this.profiles.contains(selectedProfile);
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
                invokeMethod(method, instance);
            }catch (Exception e){
                log.error("Erro ao executar metodo: {}:{} do PostCreation", method.getName(), clazz, e);
            }
        }

    }

    private List<Method> getPostCreationMethod(Class<?> clazz){
        List<Method> postCreationMethods = new ArrayList<>();
        Class<?> clazzRoot = clazz;
        while(clazzRoot != null){
            postCreationMethods.addAll(
                    Arrays.stream(clazzRoot.getDeclaredMethods())
                            .filter(c -> c.isAnnotationPresent(PostCreation.class))
                            .toList()
            );
            clazzRoot = clazzRoot.getSuperclass();
        }

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

    private Object[] tryResolveConstructorArgs(Parameter[] parameters, Object[] extraArgs, List<Parameter> failedParams) {
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
                Object injected = getDependecyObjectByParam(parameter);
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
        throwIfUnload();
        try{
            return (T)createObject(referenceClass, aop);
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    private boolean isAop(Class<?> clazz){
        if(clazz.isAnnotationPresent(DisableAop.class) || clazz.isAnnotationPresent(Aspect.class)) return false;

        return aop;
    }

    private boolean isAop(Method method){
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

}
