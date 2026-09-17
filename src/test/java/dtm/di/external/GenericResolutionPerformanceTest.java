package dtm.di.external;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.PerfFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("performance")
class GenericResolutionPerformanceTest {

    private static final int GENERIC_PAIRS = 60;
    private static final int LAYERS = 4;
    private static final int PER_LAYER = 40;
    private static final int CONFIG_BEANS = 10;
    private static final int ASYNC_PAIRS = 30;

    private static final int WARMUP_ROUNDS = 3;
    private static final int REPETITIONS = 5;

    private static final double REGISTRATION_BUDGET = 1.30;
    private static final double INJECTION_BUDGET = 1.20;
    private static final double CACHE_BUDGET = 1.10;
    private static final long ABSURD_MILLIS = 30_000;
    private static final double NOISE_FLOOR_MILLIS = 5.0;
    private static final int INJECTION_ROUNDS = 200;

    private static ExternalModule module;
    private static List<Class<?>> classes;

    @BeforeAll
    static void compileModule() {
        module = ExternalModule.compile(
                "perf-generics",
                PerfFixtures.sources(LAYERS, PER_LAYER, CONFIG_BEANS, GENERIC_PAIRS, ASYNC_PAIRS)
        );

        classes = new ArrayList<>();
        classes.addAll(module.load(PerfFixtures.serviceNames(LAYERS, PER_LAYER).toArray(String[]::new)));
        classes.addAll(module.load(PerfFixtures.genericNames(GENERIC_PAIRS).toArray(String[]::new)));
        classes.addAll(module.load(PerfFixtures.asyncNames(ASYNC_PAIRS).toArray(String[]::new)));
    }

    @AfterAll
    static void closeModule() {
        module.close();
    }

    @Test
    @DisplayName("1. a resolucao generica nao degrada o registro nem a injecao acima do orcamento")
    void genericResolutionStaysWithinBudget() throws Exception {
        warmup();

        Sample baseline = new Sample("registro + injecao, genericResolution=false");
        Sample generic = new Sample("registro + injecao, genericResolution=true");

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            measureRegistrationInto(baseline, false);
            measureRegistrationInto(generic, true);
        }

        report(baseline, generic);

        assertTrue(baseline.median() < ABSURD_MILLIS, "baseline ficou absurdamente lento: " + baseline.median() + "ms");
        assertTrue(generic.median() < ABSURD_MILLIS, "resolucao generica ficou absurdamente lenta: " + generic.median() + "ms");

        assertWithinBudget("registro + injecao", generic, baseline, REGISTRATION_BUDGET);
    }

    @Test
    @DisplayName("2. a injecao por match generico exato fica dentro do orcamento")
    void injectionStaysWithinBudget() throws Exception {
        warmup();

        Sample baseline = new Sample("injecao de consumidores genericos, genericResolution=false");
        Sample generic = new Sample("injecao de consumidores genericos, genericResolution=true");

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            measureInjectionInto(baseline, false);
            measureInjectionInto(generic, true);
        }

        report(baseline, generic);

        assertWithinBudget("injecao", generic, baseline, INJECTION_BUDGET);
    }

    @Test
    @DisplayName("3. o cache de hierarquia generica evita recomputar no segundo ciclo")
    void genericHierarchyCacheIsEffective() throws Exception {
        warmup();

        Sample first = new Sample("primeiro ciclo");
        Sample second = new Sample("ciclo seguinte");

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
            try {
                container.setGenericResolutionEnabled(true);

                long firstStart = System.nanoTime();
                container.loadExternal(classes);
                first.add(millisSince(firstStart));

                container.unload(classes);

                long secondStart = System.nanoTime();
                container.loadExternal(classes);
                second.add(millisSince(secondStart));

                container.unload(classes);
            } finally {
                ContainerFixture.dispose(container);
            }
        }

        report(first, second);

        assertWithinBudget(
                "segundo ciclo de registro (cache de hierarquia generica)",
                second,
                first,
                CACHE_BUDGET
        );
    }

    private void warmup() throws Exception {
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
            try {
                container.setGenericResolutionEnabled(round % 2 == 0);
                container.loadExternal(classes);
                container.unload(classes);
            } finally {
                ContainerFixture.dispose(container);
            }
        }
    }

    private void measureRegistrationInto(Sample sample, boolean genericResolution) throws Exception {
        DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
        try {
            container.setGenericResolutionEnabled(genericResolution);

            long start = System.nanoTime();
            container.loadExternal(classes);
            sample.add(millisSince(start));

            assertEquals(classes.size(), ContainerFixture.externalRegistrationsOf(container).size());
        } finally {
            ContainerFixture.dispose(container);
        }
    }

    private void measureInjectionInto(Sample sample, boolean genericResolution) throws Exception {
        DependencyContainerStorage container = ContainerFixture.newLoadedContainer("test");
        try {
            container.setGenericResolutionEnabled(genericResolution);
            container.loadExternal(classes);

            long start = System.nanoTime();
            for (int round = 0; round < INJECTION_ROUNDS; round++) {
                for (Class<?> clazz : classes) {
                    container.getDependency(clazz);
                }
            }
            sample.add(millisSince(start));
        } finally {
            ContainerFixture.dispose(container);
        }
    }

    private static void assertWithinBudget(String scenario, Sample candidate, Sample reference, double budget) {
        if (candidate.median() <= NOISE_FLOOR_MILLIS && reference.median() <= NOISE_FLOOR_MILLIS) {
            return;
        }

        double observed = ratio(candidate.median(), reference.median());
        assertTrue(
                observed <= budget,
                scenario + " degradou " + format(observed) + "x (orcamento " + budget + "x): "
                        + reference.median() + "ms -> " + candidate.median() + "ms"
        );
    }

    private static double ratio(double candidate, double reference) {
        return (reference <= 0) ? 1.0 : candidate / reference;
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void report(Sample... samples) {
        System.out.println();
        System.out.printf("%-60s %10s %10s %10s%n", "cenario", "min", "mediana", "max");
        for (Sample sample : samples) {
            System.out.printf(
                    "%-60s %9.0fms %9.0fms %9.0fms%n",
                    sample.name(),
                    sample.min(),
                    sample.median(),
                    sample.max()
            );
        }
    }

    private static final class Sample {

        private final String name;
        private final List<Long> values = new ArrayList<>();

        private Sample(String name) {
            this.name = name;
        }

        void add(long millis) {
            values.add(millis);
        }

        String name() {
            return name;
        }

        double min() {
            return values.stream().mapToLong(Long::longValue).min().orElse(0);
        }

        double max() {
            return values.stream().mapToLong(Long::longValue).max().orElse(0);
        }

        double median() {
            if (values.isEmpty()) {
                return 0;
            }
            List<Long> ordered = new ArrayList<>(values);
            Collections.sort(ordered);
            int middle = ordered.size() / 2;
            return (ordered.size() % 2 == 0)
                    ? (ordered.get(middle - 1) + ordered.get(middle)) / 2.0
                    : ordered.get(middle);
        }
    }
}
