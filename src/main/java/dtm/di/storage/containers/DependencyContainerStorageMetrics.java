package dtm.di.storage.containers;

import dtm.di.annotations.*;
import dtm.di.annotations.aop.Aspect;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.metrics.PrintStremFile;
import dtm.di.common.ConcurrentStopWatch;
import dtm.di.common.StopWatch;
import dtm.di.core.ClassFinderDependencyContainer;
import dtm.di.core.DependencyContainer;
import dtm.di.exceptions.*;
import dtm.di.prototypes.Dependency;
import dtm.di.prototypes.LazyDependency;
import dtm.di.prototypes.RegistrationFunction;
import dtm.di.prototypes.proxy.ProxyFactory;
import dtm.di.sort.TopologicalSorter;
import dtm.di.storage.ClassFinderConfigurationsStorage;
import dtm.di.storage.DependencyObject;
import dtm.di.storage.ServiceBean;
import dtm.di.storage.StaticContainer;
import dtm.di.storage.lazy.Lazy;
import dtm.di.storage.lazy.LazyObject;
import dtm.discovery.core.ClassFinder;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.finder.ClassFinderService;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
@Slf4j
public class DependencyContainerStorageMetrics implements DependencyContainer, ClassFinderDependencyContainer {
    private final StopWatch stopWatch;
    private final ExecutorService mainExecutor;
    private final ExecutorService mainVirtualExecutor;

    private final Map<Class<?>, List<Dependency>> dependencyContainer;
    private final ClassFinder classFinder;
    private final AtomicBoolean loaded;

    private final List<String> foldersToLoad;

    private final List<ServiceBean> serviceBeensDefinition;
    private final List<Set<ServiceBean>> serviceBeensDefinitionLayer;

    private final Set<Class<?>> loadedSystemClasses;

    private final Map<Class<?>, List<Method>> externalBeenBefore;
    private final Map<Class<?>, List<Method>> externalBeenAfter;

    private final Class<?> mainClass;
    private final List<String> profiles;
    private boolean childrenRegistration;
    private boolean parallelInjection;
    private boolean aop;
    private boolean processInlayer = true;

    @Getter
    @Setter
    private ClassFinderConfigurations classFinderConfigurations;

    public static DependencyContainerStorageMetrics getInstance(Class<?> mainClass, String... profiles){
        DependencyContainerStorageMetrics containerStorage = StaticContainer.getDependencyContainer(DependencyContainerStorageMetrics.class);
        if(containerStorage == null){
            return StaticContainer.trySetDependencyContainer(new DependencyContainerStorageMetrics(mainClass, profiles));
        }
        return containerStorage;
    }

    public static DependencyContainerStorageMetrics getLoadedInstance(){
        DependencyContainerStorageMetrics containerStorage = StaticContainer.getDependencyContainer(DependencyContainerStorageMetrics.class);
        if(containerStorage == null){
            throw new UnloadError("DependencyContainerStorage unload");
        }

        return containerStorage;
    }

    public static void loadInstance(Class<?> mainClass, String... profiles){
        StaticContainer.trySetDependencyContainer(new DependencyContainerStorageMetrics(mainClass, profiles));
    }



    private DependencyContainerStorageMetrics(Class<?> mainClass, String... profiles){
        this.stopWatch = new ConcurrentStopWatch("DependencyContainer");
        this.mainExecutor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        this.mainVirtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.dependencyContainer = new ConcurrentHashMap<>();
        this.loaded = new AtomicBoolean(false);
        this.classFinder = new ClassFinderService();
        this.childrenRegistration = false;
        this.parallelInjection = true;
        this.foldersToLoad = new ArrayList<>();
        this.serviceBeensDefinition = new Vector<>();
        this.loadedSystemClasses = ConcurrentHashMap.newKeySet();
        this.serviceBeensDefinitionLayer = Collections.synchronizedList(new ArrayList<>());
        this.externalBeenBefore = new ConcurrentHashMap<>();
        this.externalBeenAfter = new ConcurrentHashMap<>();
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
            stopWatch.start();
            if(isLoaded()) {
                stopWatch.lap("check isLoaded");
                return;
            }
            stopWatch.lap("check isLoaded");

            loadByPluginFolder();
            stopWatch.lap("loadByPluginFolder");

            loadSystemClasses();
            stopWatch.lap("loadSystemClasses");

            filterServiceClass();
            stopWatch.lap("filterServiceClass");

            filterExternalsBeens();
            stopWatch.lap("filterExternalsBeens");

            loaded.set(true);
            stopWatch.lap("loaded.set(true)");

            selfInjection();
            stopWatch.lap("selfInjection");

            registerExternalBeens(externalBeenBefore);
            stopWatch.lap("registerExternalBeens before");

            loadBeens();
            stopWatch.lap("loadBeens");

            registerExternalBeens(externalBeenAfter);
            stopWatch.lap("registerExternalBeens after");
        }catch (Exception e){
           throw new UnloadError("load error", e);
        }finally {
            CompletableFuture.runAsync(() -> stopWatch.print(getPrintStream()));
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
    public void enableParallelInjection() {
        this.parallelInjection = true;
    }

    @Override
    public void disableParallelInjection() {
        this.parallelInjection = false;
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
    public <T> T getDependency(Class<T> reference) {
        return getDependency(reference, getQualifierName(reference));
    }

    @Override
    public <T> T getDependency(Class<T> reference, String qualifier) {
        throwIfUnload();
        try{
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(reference, Collections.emptyList());
            final Dependency dependencyObject = listOfDependency.stream().filter(d -> d.getQualifier().equals(qualifier)).findFirst().orElseThrow();
            Object instance = dependencyObject.getDependency();
            return reference.cast(instance);
        }catch (Exception e){
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
            return (T)createObject(referenceClass, false);
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }



    @Override
    public <T> T newInstance(Class<T> referenceClass, Object... contructorArgs) throws NewInstanceException {
        throwIfUnload();
        try{
            return (T)createObject(referenceClass, false, contructorArgs);
        }catch (Exception e){
            throw new NewInstanceException(e.getMessage(), referenceClass, e);
        }
    }

    @Override
    public void injectDependencies(Object instance) {
        throwIfUnload();
        if(instance == null) return;

        final Class<?> clazz = instance.getClass();

        List<Field> listOfRegistration = Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> {
                    return f.isAnnotationPresent(Inject.class);
                })
                .toList();

        if(parallelInjection){
            final List<CompletableFuture<?>> tasks = new ArrayList<>();
            ExecutorService executorService = (listOfRegistration.size() > 10) ? mainExecutor : mainVirtualExecutor;
            for (Field variable : listOfRegistration) {
                CompletableFuture<?> task = CompletableFuture.runAsync(() -> {
                    injectVariable(variable, instance);
                }, executorService);
                tasks.add(task);
            }

            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        }else{
            for (Field variable : listOfRegistration) {
                injectVariable(variable, instance);
            }
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
        stopWatch.lap("loadBeens start");
        if(processInlayer){
            loadBeensInlayer();
        }else{
            loadBeensTopological();
        }
        stopWatch.lap("loadBeens end");
    }

    private void loadBeensInlayer() throws InvalidClassRegistrationException{
        for (Set<ServiceBean> layer : serviceBeensDefinitionLayer) {
            loadBeensInlayer(layer);
        }
    }

    private void loadBeensInlayer(Set<ServiceBean> layer) throws InvalidClassRegistrationException{
        stopWatch.lap("Start Layer -> " + layer.stream().map(s -> s.getClazz().getSimpleName()).toList());

        List<CompletableFuture<?>> tasks = new ArrayList<>();
        for (ServiceBean serviceBean : layer) {
            CompletableFuture<?> task = CompletableFuture.runAsync(() -> {
                try {
                    stopWatch.lap("Start loadBeen -> " + serviceBean.getClazz().getName());

                    loadBeen(serviceBean, new HashSet<>(), getQualifierName(serviceBean.getClazz()));

                    stopWatch.lap("Finish loadBeen -> " + serviceBean.getClazz().getName());
                } catch (InvalidClassRegistrationException e) {
                    stopWatch.lap("Error loading -> " + serviceBean.getClazz().getName());
                    throw new RuntimeException(e);
                }
            });
            tasks.add(task);
        }

        try {
            stopWatch.lap("Waiting Layer Finish -> " + layer.stream().map(s -> s.getClazz().getSimpleName()).toList());

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

            stopWatch.lap("Finish Layer -> " + layer.stream().map(s -> s.getClazz().getSimpleName()).toList());

        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidClassRegistrationException) {
                throw (InvalidClassRegistrationException) cause;
            } else {
                throw e;
            }
        }
    }

    private void loadBeensTopological() throws InvalidClassRegistrationException{
        stopWatch.lap("Start Topological Load");

        for (ServiceBean service : serviceBeensDefinition) {
            stopWatch.lap("Start loadBeen -> " + service.getClazz().getName());

            loadBeen(service, new HashSet<>(), getQualifierName(service.getClazz()));

            stopWatch.lap("Finish loadBeen -> " + service.getClazz().getName());
        }

        stopWatch.lap("Finish Topological Load");
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
        stopWatch.lap("filterServiceClass -> START");

        final int threshold = 50;
        stopWatch.lap("Get concrete service classes -> START");
        final Set<Class<?>> serviceLoadedClassActive = getConcreteServiceLoadedClass(Component.class);
        stopWatch.lap("Get concrete service classes -> END");

        final int total = serviceLoadedClassActive.size();
        final Map<Class<?>, Set<Class<?>>> dependencyGraph = new ConcurrentHashMap<>();

        if (total < threshold) {
            stopWatch.lap("Dependency graph -> PROCESS using ParallelStream -> START");
            processDependencyServiceWithParallelStream(dependencyGraph, serviceLoadedClassActive);
            stopWatch.lap("Dependency graph -> PROCESS using ParallelStream -> END");
        } else {
            stopWatch.lap("Dependency graph -> PROCESS using ExecutorService -> START");
            processDependencyServiceWithExecutorService(dependencyGraph, serviceLoadedClassActive);
            stopWatch.lap("Dependency graph -> PROCESS using ExecutorService -> END");
        }

        stopWatch.lap("Dependency graph -> COMPLETED");

        if (processInlayer) {
            stopWatch.lap("Process in Layer mode -> START");

            List<Set<Class<?>>> classLayers = groupByDependencyLayer(serviceLoadedClassActive, dependencyGraph);
            stopWatch.lap("Group by dependency layers -> COMPLETED");

            int order = 0;
            for (Set<Class<?>> classSet : classLayers) {
                int layerOrder = order;
                Set<ServiceBean> layer = ConcurrentHashMap.newKeySet();

                stopWatch.lap("Processing LAYER " + layerOrder + " -> START");

                classSet.parallelStream().forEach(clazz -> {
                    layer.add(new ServiceBean(clazz, layerOrder, isAop(clazz)));
                });

                serviceBeensDefinitionLayer.add(layer);

                stopWatch.lap("Processing LAYER " + layerOrder + " -> END");
                order++;
            }

            stopWatch.lap("Process in Layer mode -> END");
        } else {
            stopWatch.lap("Topological sort -> START");
            Set<Class<?>> ordered = TopologicalSorter.sort(serviceLoadedClassActive, dependencyGraph);
            stopWatch.lap("Topological sort -> END");

            stopWatch.lap("Create ServiceBean definitions (Topological) -> START");
            int order = 0;
            for (Class<?> clazz : ordered) {
                serviceBeensDefinition.add(new ServiceBean(clazz, order++, isAop(clazz)));
            }
            stopWatch.lap("Create ServiceBean definitions (Topological) -> END");
        }

        stopWatch.lap("filterServiceClass -> END");
    }

    private void loadByPluginFolder(){
        stopWatch.lap("loadByPluginFolder start");
        for (String forderPath : foldersToLoad){
            classFinder.loadByDirectory(forderPath);
            stopWatch.lap("loadByPluginFolder - " + forderPath);
        }
        stopWatch.lap("loadByPluginFolder end");
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
        Set<Class<?>> externalBeen = getConcreteServiceLoadedClass(Configuration.class);
        final Map<Class<?>, Set<Class<?>>> dependencyGraph = new ConcurrentHashMap<>();
        Set<Class<?>> ordered = TopologicalSorter.sort(externalBeen, dependencyGraph);
        for(Class<?> clazz : ordered){
            long count = getDependencyCount(clazz);

            if(count > 1){
                this.externalBeenAfter.put(clazz, getMethodsListToBeen(clazz, false));
            }else{
                this.externalBeenBefore.put(clazz, getMethodsListToBeen(clazz, true));
            }
        }
    }

    private long getDependencyCount(Class<?> clazz){

        long fieldInjectionCount = Arrays
                .stream(clazz.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Inject.class))
                .count();

        int constructorArgCount = Arrays
                .stream(clazz.getDeclaredConstructors())
                .mapToInt(Constructor::getParameterCount)
                .max()
                .orElse(0);

        return fieldInjectionCount + constructorArgCount;
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

    private List<Method> getMethodsListToBeen(Class<?> configurationsClass, boolean order){
        return Arrays.stream(configurationsClass.getDeclaredMethods())
                .filter(method -> {
                    if(method.getReturnType().equals(Void.class)){
                        return false;
                    }
                    return hasMetaAnnotation(method, Component.class) && !method.isSynthetic();
                })
                .sorted(Comparator.comparingInt(Method::getParameterCount))
                .toList();
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
                            e.printStackTrace();
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
            Object object = (aop) ? proxyObject(instance, clazz) : instance;
            stopWatch.lap("executePostCreationMethod: "+clazz);
            executePostCreationMethod(clazz, object);
            stopWatch.lap("End executePostCreationMethod: "+clazz);
            return object;
        }catch (Exception e) {
            String message = "Erro ao criar Objeto "+clazz+" ==> cause: "+e.getMessage();
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
                    Object object = (aop) ? proxyObject(instance, clazz) : instance;
                    stopWatch.lap("executePostCreationMethod: "+clazz);
                    executePostCreationMethod(clazz, object);
                    stopWatch.lap("End executePostCreationMethod: "+clazz);
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
            throw new NewInstanceException(message, clazz);
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
        stopWatch.lap("newInstance: "+clazz);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        stopWatch.lap("End newInstance: "+clazz);
        return instance;
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
            try{
                return createWithOutConstructor(clazz);
            }catch (Exception ignored){
                return null;
            }
        }
    }

    private Object getDependecyObjectByParam(Parameter parameter){
        final LazyObject lazyObject = extractType(parameter);
        final Class<?> clazzVariable = lazyObject.getClazz();
        final boolean isLazy = lazyObject.isLazy();

        if(!isLazy){
            return getDependency(clazzVariable);
        }else{
            return Lazy.of(() -> getDependency(clazzVariable));
        }
    }

    private LazyObject extractType(Field field){
        Class<?> fieldType = field.getType();
        if (LazyDependency.class.isAssignableFrom(fieldType)) {
            Type genericType = field.getGenericType();

            if (genericType instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();

                if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
                    return new LazyObject((Class<?>) typeArgs[0], true);
                }
            }
        }
        return new LazyObject(fieldType, false);
    }

    private LazyObject extractType(Parameter parameter){
        Class<?> fieldType = parameter.getType();
        Type genericType = parameter.getParameterizedType();

        if (LazyDependency.class.isAssignableFrom(fieldType)) {
            if (genericType instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
                    return new LazyObject((Class<?>) typeArgs[0], true);
                }
            }
        }

        return new LazyObject(fieldType, false);
    }

    private void injectVariable(Field variable, Object instance){
        try{
            final LazyObject lazyObject = extractType(variable);
            final Class<?> clazzVariable = lazyObject.getClazz();
            final boolean isLazy = lazyObject.isLazy();

            if(!variable.canAccess(instance)){
                variable.setAccessible(true);
            }

            if(!isLazy){
                Object targetInstance = getObjectToInjectVariable(variable, clazzVariable, instance);
                variable.set(instance, targetInstance);
            }else{
                Supplier<Object> getDependecyLazy = () -> {
                    try{
                        return getObjectToInjectVariable(variable, clazzVariable, instance);
                    }catch (Exception e){
                        String message = "Erro ao definir variavel "+variable.getName()+" ==> cause: "+e.getMessage();
                        System.out.println(message);
                        return null;
                    }
                };
                variable.set(instance, Lazy.of(getDependecyLazy));
            }

        }catch (Exception e){
            String message = "Erro ao definir variavel "+variable.getName()+" ==> cause: "+e.getMessage();
            System.out.println(message);
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

        if (superClass != null && !superClass.equals(Object.class) && !superClass.isInterface()) {
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
            System.out.println("Erro ao criar o proxy: "+e.getMessage()+" usando o objeto real.");
        }

        return realInstance;
    }

    private boolean executeProxy(Object instance){
        if (instance instanceof DependencyContainer) {
            return false;
        }
        return true;
    }

    private void loadSystemClasses(){
        stopWatch.lap("loadSystemClasses start");
        if(mainClass != null){
            loadedSystemClasses.addAll(classFinder.find(mainClass, classFinderConfigurations));
            stopWatch.lap("find all by mainClass classes");
        }else{
            loadedSystemClasses.addAll(classFinder.find(classFinderConfigurations));
            stopWatch.lap("find all classes");
        }
        stopWatch.lap("loadSystemClasses end");
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

    private boolean hasMetaAnnotation(Class<?> targetClass, Class<? extends Annotation> baseAnnotation){
        if(targetClass.isAnnotationPresent(baseAnnotation)){
            return true;
        }
        Set<Class<? extends Annotation>> visiting = new HashSet<>();
        for (Annotation annotation : targetClass.getAnnotations()) {
            if (hasMetaAnnotation(annotation.annotationType(), baseAnnotation, visiting)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMetaAnnotation(Method targetMethod, Class<? extends Annotation> baseAnnotation){
        if(targetMethod.isAnnotationPresent(baseAnnotation)){
            return true;
        }
        Set<Class<? extends Annotation>> visiting = new HashSet<>();
        for (Annotation annotation : targetMethod.getAnnotations()) {
            if (hasMetaAnnotation(annotation.annotationType(), baseAnnotation, visiting)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMetaAnnotation(Class<? extends Annotation> annotationType, Class<? extends Annotation> baseAnnotation, Set<Class<? extends Annotation>> visiting){
        if (annotationType.equals(baseAnnotation)) {
            return true;
        }
        if (!visiting.add(annotationType)) {
            return false;
        }

        for (Annotation metaAnnotation : annotationType.getAnnotations()) {
            if (hasMetaAnnotation(metaAnnotation.annotationType(), baseAnnotation, visiting)) {
                visiting.remove(annotationType);
                return true;
            }
        }
        visiting.remove(annotationType);
        return false;
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

    private List<Set<Class<?>>> groupByDependencyLayer(
            Set<Class<?>> serviceLoadedClass,
            Map<Class<?>, Set<Class<?>>> dependencyGraph) {

        List<Set<Class<?>>> layers = new ArrayList<>();
        Set<Class<?>> processed = new HashSet<>();


        Set<Class<?>> currentLayer = serviceLoadedClass.stream()
                .filter(c -> dependencyGraph.getOrDefault(c, Set.of()).isEmpty())
                .collect(Collectors.toSet());

        while (!currentLayer.isEmpty()) {
            layers.add(currentLayer);
            processed.addAll(currentLayer);

            currentLayer = serviceLoadedClass.stream()
                    .filter(c -> !processed.contains(c))
                    .filter(c -> {
                        Set<Class<?>> deps = dependencyGraph.getOrDefault(c, Set.of());
                        return processed.containsAll(deps);
                    })
                    .collect(Collectors.toSet());
        }

        return layers;

    }



    private PrintStream getPrintStream(){
        try{
            if(mainClass.isAnnotationPresent(PrintStremFile.class)){
                PrintStremFile printStremFile = mainClass.getAnnotation(PrintStremFile.class);
                String path = printStremFile.value();
                path = path.replace("${user.home}", System.getProperty("user.home"));
                path = path.replace("${user.dir}", System.getProperty("user.dir"));

                Path filePath = Paths.get(path);
                Path parent = filePath.getParent();

                if (parent != null) {
                    Files.createDirectories(parent);
                }

                if (!Files.exists(filePath)) {
                    Files.createFile(filePath);
                }
                return new PrintStream(Files.newOutputStream(filePath, StandardOpenOption.APPEND));
            }
            return System.out;
        }catch (Exception e){
            return System.out;
        }
    }

    private Constructor<?> getSelectedConstructor(Constructor<?>[] constructors, Class<?> clazz){
        if(constructors == null || constructors.length == 0) throw new NewInstanceException("construtor não encontrado para: "+clazz, clazz);

        return Arrays.stream(constructors)
                .filter(c -> c.isAnnotationPresent(MainConstructor.class))
                .findFirst()
                .orElse(constructors[0]);
    }

    private void executePostCreationMethod(Class<?> clazz, Object instance){
        stopWatch.lap("getPostCreationMethods: "+clazz);
        List<Method> postCreationMethods = getPostCreationMethod(clazz);
        stopWatch.lap("End getPostCreationMethods: "+clazz);

        for(Method method: postCreationMethods){
            try{
                stopWatch.lap("invokePostCreationMethods: "+method + " : "+clazz);
                invokeMethod(method, instance);
                stopWatch.lap("End invokePostCreationMethods: "+method + " : "+clazz);;
            }catch (Exception e){
                log.error("Erro ao executar metodo: {} do PostCreation", method.getName());
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



}
