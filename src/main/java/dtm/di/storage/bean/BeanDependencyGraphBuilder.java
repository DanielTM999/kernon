package dtm.di.storage.bean;

import dtm.di.annotations.BeanDefinition;
import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.common.AnnotationsUtils;
import dtm.di.prototypes.RegistrationFunction;
import dtm.di.prototypes.async.AsyncComponent;
import dtm.di.prototypes.async.AsyncRegistrationFunction;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.*;
import java.util.*;

@Slf4j
public class BeanDependencyGraphBuilder {

    private final Set<Class<?>> serviceClasses;
    private final Map<String, BeanInfo> allBeans;
    private final Map<Class<?>, String> typeToBeanId;

    public BeanDependencyGraphBuilder(Set<Class<?>> serviceClasses) {
        this.serviceClasses = serviceClasses;
        this.allBeans = new LinkedHashMap<>();
        this.typeToBeanId = new HashMap<>();
    }

    public BeanGraph buildGraph(Set<Class<?>> configClasses) {
        extractBeanDefinitions(configClasses);
        resolveDependencies();
        calculateLayers();
        return new BeanGraph(allBeans, typeToBeanId);
    }

    private void extractBeanDefinitions(Set<Class<?>> configClasses) {
        for (Class<?> configClass : configClasses) {
            String configBeanId = configClass.getName();
            Set<String> configDeps = extractClassDependencies(configClass);

            BeanInfo configBean = BeanInfo.builder()
                    .beanId(configBeanId)
                    .producedType(configClass)
                    .configClass(configClass)
                    .method(null)
                    .dependencies(configDeps)
                    .singleton(true)
                    .aop(false)
                    .build();

            allBeans.put(configBeanId, configBean);
            typeToBeanId.put(configClass, configBeanId);

            extractBeanMethods(configClass);
        }
    }

    private Set<String> extractClassDependencies(Class<?> clazz) {
        Set<String> deps = new HashSet<>();

        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Inject.class)) {
                deps.add(field.getType().getName());
            }
        }

        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            for (Parameter param : constructor.getParameters()) {
                deps.add(param.getType().getName());
            }
        }

        return deps;
    }

    private void extractBeanMethods(Class<?> configClass) {
        for (Method method : configClass.getDeclaredMethods()) {
            if (!isBeanMethod(method)) {
                continue;
            }

            String beanId = configClass.getName() + "." + method.getName();
            Class<?> returnType = extractProcucedType(method);

            Set<String> methodDeps = new HashSet<>();
            methodDeps.add(configClass.getName());

            for (Parameter param : method.getParameters()) {
                methodDeps.add(param.getType().getName());
            }

            BeanInfo beanDef = BeanInfo.builder()
                    .beanId(beanId)
                    .producedType(returnType)
                    .configClass(configClass)
                    .method(method)
                    .dependencies(methodDeps)
                    .singleton(isSingletonBean(method))
                    .aop(isAopEnabled(method))
                    .build();

            allBeans.put(beanId, beanDef);
            typeToBeanId.putIfAbsent(returnType, beanId);
        }
    }

    private Class<?> extractProcucedType(Method method){
        Class<?> returnType = method.getReturnType();

        if(AsyncRegistrationFunction.class.isAssignableFrom(returnType)){
            returnType = AsyncComponent.class;
        }else if(RegistrationFunction.class.isAssignableFrom(returnType)){
            Type genericReturnType = method.getGenericReturnType();

            if (genericReturnType instanceof ParameterizedType parameterizedType) {
                Type[] typeArguments = parameterizedType.getActualTypeArguments();

                if (typeArguments.length > 0) {
                    Type actualType = typeArguments[0];
                    if (actualType instanceof Class<?> actualClass) {
                        returnType = actualClass;
                    } else if (actualType instanceof ParameterizedType pt) {
                        returnType = (Class<?>) pt.getRawType();
                    }
                }
            }
        }

        return returnType;
    }

    private void resolveDependencies() {
        for (BeanInfo bean : allBeans.values()) {
            Set<String> resolvedDeps = new HashSet<>();
            boolean hasServiceDependency = false;

            for (String depTypeName : bean.getDependencies()) {
                try {
                    Class<?> depType = Class.forName(depTypeName);
                    String producerBeanId = findBeanProducer(depType);

                    if (producerBeanId != null) {
                        resolvedDeps.add(producerBeanId);
                    } else {
                        if (isUserService(depType)) {
                            hasServiceDependency = true;
                        }
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("Tipo não encontrado: {}", depTypeName);
                }
            }

            bean.setDependencies(resolvedDeps);
            bean.setDependsOnUserServices(hasServiceDependency);
        }
    }

    private String findBeanProducer(Class<?> type) {
        String exactMatch = typeToBeanId.get(type);
        if (exactMatch != null) {
            return exactMatch;
        }

        for (Map.Entry<Class<?>, String> entry : typeToBeanId.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    private void calculateLayers() {
        Map<String, Integer> layers = new HashMap<>();
        Set<String> visited = new HashSet<>();

        for (BeanInfo bean : allBeans.values()) {
            calculateLayer(bean.getBeanId(), layers, visited);
        }

        for (BeanInfo bean : allBeans.values()) {
            bean.setLayer(layers.getOrDefault(bean.getBeanId(), 0));
        }
    }

    private int calculateLayer(String beanId, Map<String, Integer> layers, Set<String> visited) {
        if (layers.containsKey(beanId)) {
            return layers.get(beanId);
        }

        if (visited.contains(beanId)) {
            throw new IllegalStateException("Dependência circular detectada: " + beanId);
        }

        visited.add(beanId);

        BeanInfo bean = allBeans.get(beanId);
        if (bean == null) {
            visited.remove(beanId);
            return 0;
        }

        int maxDepLayer = -1;
        for (String depId : bean.getDependencies()) {
            int depLayer = calculateLayer(depId, layers, visited);
            maxDepLayer = Math.max(maxDepLayer, depLayer);
        }

        int layer = maxDepLayer + 1;
        layers.put(beanId, layer);
        visited.remove(beanId);

        return layer;
    }

    private boolean isBeanMethod(Method method) {
        return AnnotationsUtils.hasMetaAnnotation(method, Component.class);
    }

    private boolean isSingletonBean(Method method) {
        if (method.isAnnotationPresent(BeanDefinition.class)) {
            return method.getAnnotation(BeanDefinition.class).proxyType() == BeanDefinition.ProxyType.STATIC;
        }
        return true;
    }

    private boolean isAopEnabled(Method method) {
        return !method.isAnnotationPresent(DisableAop.class);
    }

    private boolean isUserService(Class<?> type) {
        if (type.isPrimitive() || type.getName().startsWith("java.")) {
            return false;
        }
        return serviceClasses.contains(type) || serviceClasses.stream().anyMatch(type::isAssignableFrom);
    }
}