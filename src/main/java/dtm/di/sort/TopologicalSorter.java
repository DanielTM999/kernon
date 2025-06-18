package dtm.di.sort;

import dtm.di.exceptions.DependencyContainerRuntimeException;

import java.util.*;

public class TopologicalSorter {

    public static Set<Class<?>> sort(Set<Class<?>> classes, Map<Class<?>, Set<Class<?>>> graph) {
        Set<Class<?>> ordered = new HashSet<>();
        Set<Class<?>> visited = new HashSet<>();
        Set<Class<?>> visiting = new HashSet<>();

        for (Class<?> clazz : classes) {
            if (!visited.contains(clazz)) {
                topologicalSortVisit(clazz, graph, visited, visiting, ordered);
            }
        }

        return ordered;
    }

    private static void topologicalSortVisit(Class<?> clazz,
                                      Map<Class<?>, Set<Class<?>>> graph,
                                      Set<Class<?>> visited,
                                      Set<Class<?>> visiting,
                                      Set<Class<?>> ordered) {
        if (visiting.contains(clazz)) {
            throw new DependencyContainerRuntimeException("Ciclo de dependência detectado em: " + clazz.getName());
        }

        if (visited.contains(clazz)) return;

        visiting.add(clazz);

        for (Class<?> dep : graph.getOrDefault(clazz, Collections.emptySet())) {
            topologicalSortVisit(dep, graph, visited, visiting, ordered);
        }

        visiting.remove(clazz);
        visited.add(clazz);
        ordered.add(clazz);
    }

}
