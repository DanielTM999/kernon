package dtm.di.external;

import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.PerfFixtures;
import dtm.di.testsupport.Probe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("performance")
class ExternalLoadPerformanceTest {

    private static final int LAYERS = 5;
    private static final int PER_LAYER = 40;
    private static final int CONFIG_BEANS = 20;
    private static final int TOTAL_SERVICES = LAYERS * PER_LAYER;
    private static final int BATCH_SIZE = 20;
    private static final int REPETITIONS = 5;
    private static final int WARMUP_ROUNDS = 3;

    private static ExternalModule warmupModule;
    private static ExternalModule benchmarkModule;
    private static long warmupCompileMillis;
    private static long benchmarkCompileMillis;

    @BeforeAll
    static void compileModules() {
        long start = System.nanoTime();
        warmupModule = ExternalModule.compile("perf-warmup", PerfFixtures.sources(2, 5, 2));
        warmupCompileMillis = millisSince(start);

        start = System.nanoTime();
        benchmarkModule = ExternalModule.compile("perf-bench", PerfFixtures.sources(LAYERS, PER_LAYER, CONFIG_BEANS));
        benchmarkCompileMillis = millisSince(start);
    }

    @AfterAll
    static void closeModules() {
        warmupModule.close();
        benchmarkModule.close();
    }

    @Test
    @DisplayName("relatorio de performance: boot + registro de classes externas")
    void performanceReport() throws Exception {
        warmup();

        List<Class<?>> services = load(benchmarkModule, PerfFixtures.serviceNames(LAYERS, PER_LAYER));
        List<Class<?>> everything = new ArrayList<>(services);
        everything.add(benchmarkModule.load(PerfFixtures.CONFIGURATION));

        Sample boot = measureBoot();
        Sample singleBatch = measureSingleBatch(everything, false);
        Sample singleBatchAop = measureSingleBatch(everything, true);
        Sample incremental = measureIncrementalBatches(everything);
        Sample unloadAll = measureUnloadAll(everything);
        Sample cycle = measureCycles(everything);

        report(everything.size(), boot, singleBatch, singleBatchAop, incremental, unloadAll, cycle);

        assertTrue(singleBatch.median() < 30_000, "carga externa de " + everything.size() + " classes ficou absurdamente lenta");
        assertTrue(unloadAll.median() < 30_000, "descarga externa ficou absurdamente lenta");
    }

    private void warmup() throws Exception {
        List<Class<?>> classes = load(warmupModule, PerfFixtures.serviceNames(2, 5));
        classes.add(warmupModule.load(PerfFixtures.CONFIGURATION));

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
            try {
                container.loadExternal(classes);
                container.unload(classes);
            } finally {
                ContainerFixture.dispose(container);
            }
        }

        Probe.reset();
    }

    private Sample measureBoot() throws Exception {
        Sample sample = new Sample("boot: load() do container principal");

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            DependencyContainerStorage container = ContainerFixture.newContainer("test");
            try {
                long start = System.nanoTime();
                container.load();
                sample.add(millisSince(start));

                sample.setDetail(container.getLoadedSystemClasses().size() + " classes varridas no classpath");
            } finally {
                ContainerFixture.dispose(container);
            }
        }

        return sample;
    }

    private Sample measureSingleBatch(List<Class<?>> classes, boolean aop) throws Exception {
        Sample sample = new Sample("loadExternal: lote unico" + (aop ? " com AOP" : "") + " (" + classes.size() + " classes)");
        sample.setPerUnit(classes.size());

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
            try {
                if (aop) {
                    container.enableAOP();
                }

                long start = System.nanoTime();
                container.loadExternal(classes);
                sample.add(millisSince(start));

                assertEquals(classes.size(), ContainerFixture.externalRegistrationsOf(container).size());
                assertRegistered(container, classes);
            } finally {
                ContainerFixture.dispose(container);
            }
        }

        return sample;
    }

    private Sample measureIncrementalBatches(List<Class<?>> classes) throws Exception {
        int batches = (classes.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        Sample sample = new Sample("loadExternal: " + batches + " lotes de " + BATCH_SIZE);

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
            try {
                long start = System.nanoTime();

                for (int offset = 0; offset < classes.size(); offset += BATCH_SIZE) {
                    container.loadExternal(classes.subList(offset, Math.min(offset + BATCH_SIZE, classes.size())));
                }

                sample.add(millisSince(start));

                assertEquals(classes.size(), ContainerFixture.externalRegistrationsOf(container).size());
            } finally {
                ContainerFixture.dispose(container);
            }
        }

        return sample;
    }

    private Sample measureUnloadAll(List<Class<?>> classes) throws Exception {
        Sample sample = new Sample("unload: " + classes.size() + " classes de uma vez");
        sample.setPerUnit(classes.size());

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
            try {
                container.loadExternal(classes);

                long start = System.nanoTime();
                container.unload(classes);
                sample.add(millisSince(start));

                assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
                assertTrue(container.isLoaded());
            } finally {
                ContainerFixture.dispose(container);
            }
        }

        return sample;
    }

    private Sample measureCycles(List<Class<?>> classes) throws Exception {
        Sample sample = new Sample("ciclo load+unload no mesmo container");
        DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");

        try {
            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                long start = System.nanoTime();
                container.loadExternal(classes);
                container.unload(classes);
                sample.add(millisSince(start));
            }

            assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
            assertTrue(container.isLoaded());
        } finally {
            ContainerFixture.dispose(container);
        }

        return sample;
    }

    private void assertRegistered(DependencyContainerStorage container, List<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            assertTrue(container.hasDependecy(clazz) || clazz.getName().equals(PerfFixtures.CONFIGURATION),
                    () -> "classe externa nao registrada: " + clazz.getName());
        }
    }

    private List<Class<?>> load(ExternalModule module, List<String> names) {
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            classes.add(module.load(name));
        }
        return classes;
    }

    private void report(int totalClasses, Sample... samples) {
        StringBuilder report = new StringBuilder();

        report.append(System.lineSeparator())
                .append("=== Kernon | boot + carregamento externo ===").append(System.lineSeparator())
                .append("JVM ......... ").append(System.getProperty("java.vm.name"))
                .append(" ").append(System.getProperty("java.version")).append(System.lineSeparator())
                .append("CPUs ........ ").append(Runtime.getRuntime().availableProcessors()).append(System.lineSeparator())
                .append("Modulo ...... ").append(TOTAL_SERVICES).append(" componentes em ").append(LAYERS)
                .append(" camadas + 1 @Configuration com ").append(CONFIG_BEANS + 1).append(" beans")
                .append(System.lineSeparator())
                .append("Compilacao .. warmup ").append(warmupCompileMillis).append(" ms | benchmark ")
                .append(benchmarkCompileMillis).append(" ms (javac, fora da medicao)").append(System.lineSeparator())
                .append("Amostras .... ").append(REPETITIONS).append(" repeticoes apos ").append(WARMUP_ROUNDS)
                .append(" rodadas de aquecimento").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(String.format("%-52s %9s %9s %9s %9s %12s%n",
                        "cenario", "min", "mediana", "media", "max", "por classe"));

        for (Sample sample : samples) {
            report.append(String.format(
                    "%-52s %8.1fms %8.1fms %8.1fms %8.1fms %11s%n",
                    sample.name(),
                    sample.min(),
                    sample.median(),
                    sample.mean(),
                    sample.max(),
                    sample.perUnitLabel()
            ));
        }

        report.append(System.lineSeparator()).append("amostras (ordem de execucao):").append(System.lineSeparator());

        for (Sample sample : samples) {
            report.append(String.format("  %-52s %s ms%n", sample.name(), sample.values()));
        }

        report.append(System.lineSeparator());

        for (Sample sample : samples) {
            if (sample.detail() != null) {
                report.append(sample.name()).append(" -> ").append(sample.detail()).append(System.lineSeparator());
            }
        }

        report.append(System.lineSeparator())
                .append("Notas: o boot aqui varre um classpath de teste minusculo, entao mede o custo fixo,")
                .append(System.lineSeparator())
                .append("nao a varredura de uma aplicacao real. A primeira repeticao de cada cenario paga")
                .append(System.lineSeparator())
                .append("metadados de reflexao da JVM e JIT; por isso a mediana e mais representativa que a media.")
                .append(System.lineSeparator())
                .append("Total de classes externas por lote: ").append(totalClasses).append(System.lineSeparator());

        System.out.println(report);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class Sample {

        private final String name;
        private final List<Long> values = new ArrayList<>();
        private String detail;
        private int perUnit;

        private Sample(String name) {
            this.name = name;
        }

        void add(long millis) {
            values.add(millis);
        }

        String name() {
            return name;
        }

        List<Long> values() {
            return values;
        }

        void setPerUnit(int perUnit) {
            this.perUnit = perUnit;
        }

        String perUnitLabel() {
            if (perUnit <= 0) {
                return "-";
            }
            return String.format("%.3fms", median() / perUnit);
        }

        String detail() {
            return detail;
        }

        void setDetail(String detail) {
            this.detail = detail;
        }

        double min() {
            return values.stream().mapToLong(Long::longValue).min().orElse(0);
        }

        double max() {
            return values.stream().mapToLong(Long::longValue).max().orElse(0);
        }

        double mean() {
            return values.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        double median() {
            long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
            if (sorted.length == 0) {
                return 0;
            }
            int middle = sorted.length / 2;
            return sorted.length % 2 == 1
                    ? sorted[middle]
                    : (sorted[middle - 1] + sorted[middle]) / 2.0;
        }
    }
}
