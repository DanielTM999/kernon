package dtm.di.storage;

import dtm.di.annotations.*;
import dtm.di.core.DependencyContainer;
import dtm.di.exceptions.*;
import dtm.di.prototypes.Dependency;
import dtm.di.prototypes.LazyDependency;
import dtm.di.prototypes.proxy.ProxyFactory;
import dtm.di.sort.TopologicalSorter;
import dtm.di.storage.lazy.Lazy;
import dtm.di.storage.lazy.LazyObject;
import dtm.discovery.core.ClassFinder;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.ClassFinderErrorHandler;
import dtm.discovery.finder.ClassFinderService;
import lombok.NonNull;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class DependencyContainerStorage implements DependencyContainer {
    private final Map<Class<?>, List<Dependency>> dependencyContainer;
    private final ClassFinder classFinder;
    private final AtomicBoolean loaded;
    private final List<String> foldersToLoad;
    private final List<ServiceBeen> serviceBeensDefinition;
    private final Set<Class<?>> loadedSystemClasses;
    private final Set<Class<?>> externalBeenBefore;
    private final Set<Class<?>> externalBeenAfter;
    private boolean childrenRegistration;
    private boolean parallelInjection;
    private boolean aop;

    public static DependencyContainerStorage getInstance(){
        if(StaticContainer.getContainerStorage() == null){
            StaticContainer.setContainerStorage(new DependencyContainerStorage());
        }

        return StaticContainer.getContainerStorage();
    }

    private DependencyContainerStorage(){
        this.dependencyContainer = new ConcurrentHashMap<>();
        this.loaded = new AtomicBoolean(false);
        this.classFinder = new ClassFinderService();
        this.childrenRegistration = false;
        this.parallelInjection = true;
        this.foldersToLoad = new ArrayList<>();
        this.serviceBeensDefinition = new Vector<>();
        this.loadedSystemClasses = ConcurrentHashMap.newKeySet();
        this.externalBeenBefore = ConcurrentHashMap.newKeySet();
        this.externalBeenAfter = ConcurrentHashMap.newKeySet();
    }


    @Override
    public void load() throws InvalidClassRegistrationException {
        try{
            if(isLoaded()) return;
            loadByPluginFolder();
            loadedSystemClasses.addAll(classFinder.find(getFindConfigurations(null)));
            filterServiceClass();
            filterExternalsBeens();
            loaded.set(true);
            selfInjection();
            registerExternalBeens(externalBeenBefore);
            loadBeens();
            registerExternalBeens(externalBeenAfter);
            loadedSystemClasses.forEach(System.out::println);
        }catch (Exception e){
           throw new UnloadError("load error", e);
        }
    }

    @Override
    public void unload() {
        loaded.set(false);
        loadedSystemClasses.clear();
        serviceBeensDefinition.clear();
        dependencyContainer.clear();
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
            final Dependency dependencyObject = Objects.requireNonNull(listOfDependency).stream().filter(d -> d.getQualifier().equals(qualifier)).findFirst().orElseThrow();
            Object instance = dependencyObject.getDependency();
            return reference.cast(instance);
        }catch (Exception e){
            return null;
        }
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
            final List<Future<?>> tasks = new ArrayList<>();
            try(ExecutorService executorService = (listOfRegistration.size() > 10) ? Executors.newCachedThreadPool() : Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())){
                for (Field variable : listOfRegistration) {
                    tasks.add(executorService.submit(() -> injectVariable(variable, instance)));
                }
            }

            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException | ExecutionException ignored) {}
            }
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
    public void registerDependency(Object dependency, String qualifier) throws InvalidClassRegistrationException {
        registerObject(dependency, qualifier);
    }

    @Override
    public void registerDependency(Object dependency) throws InvalidClassRegistrationException {
        registerObject(dependency);
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
        for (ServiceBeen service: serviceBeensDefinition){
            loadBeen(service, new HashSet<>(), getQualifierName(service.getClazz()));
        }
    }

    private void loadBeen(ServiceBeen been, final Set<Class<?>> registeringClasses, String qualifier) throws InvalidClassRegistrationException{
        final Class<?> dependency = been.getClazz();

        try {
            if (dependencyContainer.containsKey(dependency)) return;
            validRegistration(dependency, registeringClasses);
            final List<Dependency> listOfDependency = dependencyContainer.getOrDefault(dependency, new ArrayList<Dependency>());
            validQualifier(listOfDependency, qualifier, dependency);
            if(childrenRegistration){
                registerAutoInject(dependency, registeringClasses);
            }

            DependencyObject dependencyObject = isSingleton(dependency)
                    ? new DependencyObject(dependency, qualifier, true, null, createObject(dependency))
                    : new DependencyObject(dependency, qualifier, false, createActivationFunction(dependency), null);


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
                loadBeen(new ServiceBeen(subClass, order++), registeringClasses, getQualifierName(subClass));
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
        final int threshold = 50;
        final Set<Class<?>> serviceLoadedClass = getConcreteServiceLoadedClass(Service.class);
        final int total = serviceLoadedClass.size();

        final Map<Class<?>, Set<Class<?>>> dependencyGraph = new ConcurrentHashMap<>();

        if (total < threshold) {
            processDependencyServiceWithParallelStream(dependencyGraph, serviceLoadedClass);
        } else {
            processDependencyServiceWithExecutorService(dependencyGraph, serviceLoadedClass);
        }

        List<Class<?>> ordered = TopologicalSorter.sort(serviceLoadedClass, dependencyGraph);

        int order = 0;
        for (Class<?> clazz : ordered) {
            serviceBeensDefinition.add(new ServiceBeen(clazz, order++));
        }
    }

    private boolean runInJar(){
        try{
            String className = getClass().getSimpleName() + ".class";
            URL resorcesUrl = getClass().getResource(className);
            if(resorcesUrl != null){
                String classPath = resorcesUrl.toString();
                return classPath.startsWith("jar:");
            }
            return false;
        }catch (Exception e){
            return false;
        }
    }

    private void loadByPluginFolder(){
        for (String forderPath : foldersToLoad){
            classFinder.loadByDirectory(forderPath);
        }
    }

    private ClassFinderConfigurations getFindConfigurations(Class<? extends Annotation> anotation){
        return new ClassFinderConfigurations() {
            @Override
            public boolean getAllElements() {
                return ClassFinderConfigurations.super.getAllElements();
            }

            @Override
            public boolean getAnonimousClass() {
                return false;
            }

            @Override
            public boolean ignoreSubJars() {
                return ClassFinderConfigurations.super.ignoreSubJars();
            }

            @Override
            public List<String> getIgnorePackges() {
                List<String> strings = ClassFinderConfigurations.super.getIgnorePackges();
                strings.add("net.bytebuddy");
                strings.add("lombok");
                return strings;
            }

            @Override
            public List<String> getIgnoreJarsTerms() {
                List<String> jarList = ClassFinderConfigurations.super.getIgnoreJarsTerms();
                jarList.add("lombok");
                jarList.add("byte-buddy");
                return jarList;
            }

            @Override
            public ClassFinderErrorHandler getHandler() {
                return (error) -> {};
            }

            @Override
            public Class<? extends Annotation> getFilterByAnnotation() {
                return anotation;
            }
        };
    }

    private String getQualifierName(@NonNull Class<?> clazz){
        if(clazz.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = clazz.getAnnotation(Qualifier.class);
            return (Objects.requireNonNull(qualifierAnnotation).qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? "default" : qualifierAnnotation.qualifier();
        } else {
            return  "default";
        }
    }

    private String getQualifierName(@NonNull Field variable){
        if(variable.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = variable.getAnnotation(Qualifier.class);
            return (Objects.requireNonNull(qualifierAnnotation).qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? "default" : qualifierAnnotation.qualifier();
        } else {
            return  "default";
        }
    }

    private String getQualifierName(@NonNull Parameter variable){
        if(variable.isAnnotationPresent(Qualifier.class)){
            Qualifier qualifierAnnotation = variable.getAnnotation(Qualifier.class);
            return (Objects.requireNonNull(qualifierAnnotation).qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? "default" : qualifierAnnotation.qualifier();
        } else {
            return  "default";
        }
    }

    private String getQualifierName(@NonNull Method beenMethod){
        if(beenMethod.isAnnotationPresent(Service.class)){
            Service qualifierAnnotation = beenMethod.getAnnotation(Service.class);
            return (qualifierAnnotation.qualifier() == null || qualifierAnnotation.qualifier().isEmpty()) ? "default" : qualifierAnnotation.qualifier();
        }
        return  "default";
    }

    private boolean isSingletonBeen(@NonNull Method method){
        if(method.isAnnotationPresent(BeenDefinition.class)){
            return method.getAnnotation(BeenDefinition.class).proxyType() == BeenDefinition.ProxyType.STATIC;
        }
        return true;
    }

    private boolean isSingleton(@NonNull Class<?> clazz){
        return clazz.isAnnotationPresent(Singleton.class);
    }

    private Set<Class<?>> getConcreteServiceLoadedClass(Class<? extends Annotation> anotation){
        final int threshold = 350;
        final int total = loadedSystemClasses.size();

        if (total < threshold) {
            return loadedSystemClasses.stream()
                    .parallel()
                    .filter(c-> c.isAnnotationPresent(anotation)&& !c.isInterface() && !Modifier.isAbstract(c.getModifiers()))
                    .collect(Collectors.toSet());
        }else{

            final int availableProcessors = Runtime.getRuntime().availableProcessors();
            final List<Future<Set<Class<?>>>> futures = new ArrayList<>();
            final Set<Class<?>> result = new HashSet<>();
            try(ExecutorService executorService = Executors.newFixedThreadPool(availableProcessors)){
                int chunkSize = total / availableProcessors;
                List<Class<?>> classList = new ArrayList<>(loadedSystemClasses);
                for (int i = 0; i < total; i += chunkSize) {
                    final int start = i;
                    final int end = Math.min(i + chunkSize, total);
                    futures.add(executorService.submit(() -> {
                        return classList.subList(start, end).stream()
                                .filter(c -> c.isAnnotationPresent(anotation) && !c.isInterface() && !Modifier.isAbstract(c.getModifiers()))
                                .collect(Collectors.toSet());
                    }));
                }

                for (Future<Set<Class<?>>> future : futures) {
                    try {
                        result.addAll(future.get());
                    } catch (Exception ignored) {}
                }
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
        List<Callable<Void>> tasks = serviceLoadedClass.stream()
                .filter(clazz -> !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()))
                .map(clazz -> (Callable<Void>) () -> {
                    Set<Class<?>> dependencies = getDependecyClassListOfClass(clazz, serviceLoadedClass);
                    dependencyGraph.put(clazz, dependencies);
                    return null;
                })
                .toList();

        final int threads = Runtime.getRuntime().availableProcessors();
        try(ExecutorService executorService = Executors.newFixedThreadPool(threads)){
            List<Future<Void>> futures = executorService.invokeAll(tasks);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    throw new DependencyContainerRuntimeException("Erro ao processar uma classe", e.getCause());
                }
            }
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new DependencyContainerRuntimeException("Execução interrompida", e);
        }

    }

    private void filterExternalsBeens() throws InvalidClassRegistrationException{
        List<Future<?>> tasks = new ArrayList<>();
        Set<Class<?>> externalBeen = getConcreteServiceLoadedClass(Configuration.class);
        try(ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())){
            for(Class<?> clazz : externalBeen){
                tasks.add(executor.submit(() -> {
                    long count = getDependencyCount(clazz);

                    if(count > 1){
                        this.externalBeenAfter.add(clazz);
                    }else{
                        this.externalBeenBefore.add(clazz);
                    }
                }));
            }
        }

        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DependencyContainerRuntimeException("Thread foi interrompida durante o registro de beans externos", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InvalidClassRegistrationException) {
                    throw (InvalidClassRegistrationException) cause;
                } else {
                    throw new DependencyContainerRuntimeException("Erro inesperado ao registrar beans externos", cause);
                }
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

    private void registerExternalBeens(Set<Class<?>> configurationsClasses) throws InvalidClassRegistrationException{
        List<Future<?>> tasks = new ArrayList<>();
        try(ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())){
            for(Class<?> configurationsClass : configurationsClasses){
                tasks.add(executor.submit(() -> {
                    List<Method> methodsList = getMethodsListToBeen(configurationsClass);
                    if(!methodsList.isEmpty()){
                        try {
                            registerExternalBeen(configurationsClass, methodsList);
                        } catch (InvalidClassRegistrationException e) {
                            throw new DependencyContainerRuntimeException(e);
                        }
                    }
                }));
            }
        }

        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DependencyContainerRuntimeException("Thread foi interrompida durante o registro de beans externos", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InvalidClassRegistrationException) {
                    throw (InvalidClassRegistrationException) cause;
                } else {
                    throw new DependencyContainerRuntimeException("Erro inesperado ao registrar beans externos", cause);
                }
            }
        }
    }

    private List<Method> getMethodsListToBeen(Class<?> configurationsClass){
        return Arrays.stream(configurationsClass.getDeclaredMethods())
                .filter(method -> {
                    if(method.getReturnType().equals(Void.class)){
                        return false;
                    }
                    return method.isAnnotationPresent(Service.class) && !method.isSynthetic();
                })
                .toList();
    }

    private void registerExternalBeen(Class<?> configurationsClass, List<Method> methodsList) throws InvalidClassRegistrationException{
        try {
            Object configurationInstance = newInstance(configurationsClass);
            for (Method method : methodsList) {
                Object result = null;
                Class<?>[] parameterTypes = method.getParameterTypes();
                Object[] args = new Object[parameterTypes.length];
                Arrays.fill(args, null);

                method.setAccessible(true);

                result = method.invoke(configurationInstance, args);

                if(result != null){
                    String qualifier = getQualifierName(method);
                    boolean singleton = isSingletonBeen(method);
                    if(singleton){
                        registerDependency(result, qualifier);
                    }else {
                        registerExternalBeenNoSinglenton(result, method, qualifier);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private Object createObject(@NonNull Class<?> clazz){
        return createObject(clazz, this.aop);
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
            return (aop) ? proxyObject(instance, clazz) : instance;
        }catch (Exception e) {
            String message = "Erro ao criar Objeto "+clazz+" ==> cause: "+e.getMessage();
            System.out.println(message);
        }
        return null;
    }

    private Supplier<Object> createActivationFunction(@NonNull Class<?> clazz){
        return () -> {
            return createObject(clazz);
        };
    }

    private Object createWithOutConstructor(@NonNull Class<?> clazz) throws Exception{
        return clazz.getDeclaredConstructor().newInstance();
    }

    private Object createWithConstructor(@NonNull Class<?> clazz, @NonNull Constructor<?>[] constructors){
        try{
            Constructor<?> chosenConstructor = Arrays.stream(constructors)
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow(() -> new Exception("Nenhum construtor encontrado para " + clazz.getName()));
        
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
            ServiceBeen serviceBeen = new ServiceBeen(beenClass, 0);

            loadBeen(serviceBeen, new HashSet<>(), getQualifierName(beenClass));
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
            final Object toRegistrate = (aop) ? proxyObject(dependency, clazz) : dependency;
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
            if(executeProxy(realInstance)) return ProxyFactory.newProxyObject(realInstance, clazz);
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

}
