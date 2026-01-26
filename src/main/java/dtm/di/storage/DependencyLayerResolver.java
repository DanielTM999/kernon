package dtm.di.storage;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class DependencyLayerResolver {
    private final Set<Class<?>> serviceLoadedClass;
    private final Map<Class<?>, Set<Class<?>>> dependencyGraph;

    public DependencyLayerResolver(Set<Class<?>> serviceLoadedClass, Map<Class<?>, Set<Class<?>>> dependencyGraph) {
        this.serviceLoadedClass = serviceLoadedClass;
        this.dependencyGraph = dependencyGraph;
    }

    public List<Set<Class<?>>> resolveLayers() {
        List<Set<Class<?>>> layers = new ArrayList<>();
        Set<Class<?>> processed = new HashSet<>();


        if(serviceLoadedClass.isEmpty()){
            return layers;
        }

        Set<Class<?>> currentLayer = findInitialLayer();

        if (currentLayer.isEmpty()) {
            handleCircularDependency();
        }

        while (!currentLayer.isEmpty()) {
            layers.add(currentLayer);
            processed.addAll(currentLayer);

            currentLayer = serviceLoadedClass.stream()
                    .filter(c -> !processed.contains(c))
                    .filter(c -> {
                        Set<Class<?>> deps = dependencyGraph.getOrDefault(c, Set.of());
                        Set<Class<?>> serviceDeps = deps.stream()
                                .filter(serviceLoadedClass::contains)
                                .collect(Collectors.toSet());
                        return processed.containsAll(serviceDeps);
                    })
                    .collect(Collectors.toSet());
        }

        return layers;
    }

    private Set<Class<?>> findInitialLayer() {
        return serviceLoadedClass.stream()
                .filter(c -> {
                    Set<Class<?>> allDeps = dependencyGraph.getOrDefault(c, Set.of());
                    Set<Class<?>> serviceDeps = filterServiceDependencies(allDeps);

                    boolean isLayer0 = serviceDeps.isEmpty();

                    if (isLayer0 && log.isDebugEnabled()) {
                        if (allDeps.isEmpty()) {
                            log.debug("  ✓ {} → [SEM DEPENDÊNCIAS]", c.getSimpleName());
                        } else {
                            log.debug("  ✓ {} → [Deps não-serviço: {}]",
                                    c.getSimpleName(),
                                    allDeps.stream().map(Class::getSimpleName).collect(Collectors.joining(", ")));
                        }
                    }

                    return isLayer0;
                })
                .collect(Collectors.toSet());
    }

    private Set<Class<?>> filterServiceDependencies(Set<Class<?>> dependencies) {
        return dependencies.stream()
                .filter(serviceLoadedClass::contains)
                .collect(Collectors.toSet());
    }

    private void handleCircularDependency() {
        detectAndLogCircularDependencies();
        throw new IllegalStateException("Dependência circular detectada! Verifique os logs acima.");
    }

    private void detectAndLogCircularDependencies() {
        findAndLogCycle();
    }

    private void findAndLogCycle() {
        boolean foundCycle = false;

        for (Class<?> start : serviceLoadedClass) {
            List<Class<?>> cycle = findCycle(start);
            if (cycle != null) {
                foundCycle = true;
                logCycle(cycle);
                break;
            }
        }

        if (!foundCycle) {
            log.error("\n  ⚠️  Nenhum ciclo específico encontrado, mas todas as classes têm dependências.\n" +
                    "  Possível problema: dependências não satisfeitas ou configuração incorreta.\n");
        }
    }

    private List<Class<?>> findCycle(Class<?> start) {
        return findCycleRecursive(start, start, new ArrayList<>(), new HashSet<>());
    }

    private List<Class<?>> findCycleRecursive(
            Class<?> current,
            Class<?> target,
            List<Class<?>> path,
            Set<Class<?>> visited) {

        if (visited.contains(current)) {
            return null;
        }

        path.add(current);
        visited.add(current);

        Set<Class<?>> deps = dependencyGraph.getOrDefault(current, Set.of())
                .stream()
                .filter(serviceLoadedClass::contains)
                .collect(Collectors.toSet());

        for (Class<?> dep : deps) {
            if (dep.equals(target) && path.size() > 1) {
                return new ArrayList<>(path);  // Ciclo encontrado!
            }

            List<Class<?>> result = findCycleRecursive(
                    dep, target, new ArrayList<>(path),
                    new HashSet<>(visited)
            );

            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private void logCycle(List<Class<?>> cycle) {
        StringBuilder cycleLog = new StringBuilder();
        cycleLog.append("\n╔════════════════════════════════════════════════════════════════╗\n");
        cycleLog.append("║                      ERRO CRÍTICO!                             ║\n");
        cycleLog.append("║              DEPENDÊNCIA CIRCULAR DETECTADA!                   ║\n");
        cycleLog.append("╚════════════════════════════════════════════════════════════════╝\n\n");
        cycleLog.append("  ");
        for (int i = 0; i < cycle.size(); i++) {
            cycleLog.append(cycle.get(i).getSimpleName());
            if (i < cycle.size() - 1) {
                cycleLog.append("\n  │\n  ↓\n  ");
            }
        }
        cycleLog.append("\n  │\n  ↓\n  ");
        cycleLog.append(cycle.get(0).getSimpleName()).append(" ⟲\n\n");

        String linearCycle = cycle.stream()
                .map(Class::getSimpleName)
                .collect(Collectors.joining(" → "));
        cycleLog.append("  Caminho: ").append(linearCycle).append(" → ")
                .append(cycle.get(0).getSimpleName()).append(" ⟲\n");

        log.error(cycleLog.toString());
    }

    private void logLayer(int layerNum, Set<Class<?>> currentLayer) {
        StringBuilder layerLog = new StringBuilder();
        layerLog.append("\n╭─── 📦 CAMADA ").append(layerNum).append(" ───────────────────────────────────────────╮");

        for (Class<?> c : currentLayer) {
            layerLog.append("\n│  ✓ ").append(c.getSimpleName());
        }

        layerLog.append("\n╰────────────────────────────────────────────────────────────╯");
        log.info(layerLog.toString());
    }

}
