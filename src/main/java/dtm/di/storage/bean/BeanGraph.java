package dtm.di.storage.bean;

import lombok.Getter;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

@Getter
public class BeanGraph {

    private final Map<String, BeanInfo> allBeans;
    private final Map<Class<?>, String> typeToBeanId;

    public BeanGraph(Map<String, BeanInfo> allBeans, Map<Class<?>, String> typeToBeanId) {
        this.allBeans = allBeans;
        this.typeToBeanId = typeToBeanId;
    }

    public Map<Class<?>, List<Method>> getBeforeServiceBeans(Set<Class<?>> serviceClasses) {
        return getBeansByPhase(serviceClasses, false);
    }

    public Map<Class<?>, List<Method>> getAfterServiceBeans(Set<Class<?>> serviceClasses) {
        return getBeansByPhase(serviceClasses, true);
    }

    private Map<Class<?>, List<Method>> getBeansByPhase(Set<Class<?>> serviceClasses, boolean afterServices) {
        Map<Class<?>, List<Method>> result = new LinkedHashMap<>();

        List<BeanInfo> phaseBeans = allBeans.values().stream()
                .filter(b -> dependsOnServices(b, serviceClasses) == afterServices)
                .filter(BeanInfo::isBeanMethod)
                .sorted(Comparator.comparingInt(BeanInfo::getLayer))
                .collect(Collectors.toList());

        Map<Class<?>, List<BeanInfo>> byConfigClass = new LinkedHashMap<>();

        for (BeanInfo bean : phaseBeans) {
            byConfigClass.computeIfAbsent(bean.getConfigClass(), k -> new ArrayList<>()).add(bean);
        }

        for (Map.Entry<Class<?>, List<BeanInfo>> entry : byConfigClass.entrySet()) {
            List<Method> methods = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(BeanInfo::getLayer))
                    .map(BeanInfo::getMethod)
                    .collect(Collectors.toList());

            result.put(entry.getKey(), methods);
        }

        return result;
    }

    private boolean dependsOnServices(BeanInfo bean, Set<Class<?>> serviceClasses) {
        return dependsOnServicesRecursive(bean, serviceClasses, new HashSet<>());
    }

    private boolean dependsOnServicesRecursive(BeanInfo bean, Set<Class<?>> serviceClasses, Set<String> visited) {
        if (visited.contains(bean.getBeanId())) {
            return false;
        }
        visited.add(bean.getBeanId());

        if (bean.isDependsOnUserServices()) {
            return true;
        }

        for (String depId : bean.getDependencies()) {
            BeanInfo dep = allBeans.get(depId);
            if (dep == null) continue;

            if (serviceClasses.contains(dep.getProducedType())) {
                return true;
            }

            if (serviceClasses.stream().anyMatch(svc -> svc.isAssignableFrom(dep.getProducedType()))) {
                return true;
            }

            if (dependsOnServicesRecursive(dep, serviceClasses, visited)) {
                return true;
            }
        }

        return false;
    }


}
