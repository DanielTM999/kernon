package dtm.di.external;

import dtm.di.exceptions.ExternalDependencyInUseException;
import dtm.di.prototypes.async.AsyncComponent;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalFixtures;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.Probe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalIntegrationTest {

    private static ExternalModule module;

    private DependencyContainerStorage container;

    @BeforeAll
    static void compileModule() {
        module = ExternalModule.compile("integration", ExternalFixtures.sources());
    }

    @AfterAll
    static void closeModule() {
        module.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        Probe.reset();
        container = ContainerFixture.newLoadedContainer("test");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("RegistrationFunction e AsyncRegistrationFunction externas são registradas e removidas")
    void registrationFunctionsAreSupported() throws Exception {
        Class<?> configuration = module.load(ExternalFixtures.FUNCTION_CONFIGURATION);
        Class<?> functionBean = module.load(ExternalFixtures.FUNCTION_BEAN);

        container.loadExternal(List.of(configuration));

        Object first = container.getDependency(functionBean, "function");
        Object second = container.getDependency(functionBean, "function");

        assertNotNull(first);
        assertNotSame(first, second);
        assertEquals("from-function", ContainerFixture.invoke(first, "value"));
        assertTrue(container.hasDependecy(AsyncComponent.class, "asyncFunction"));
        assertTrue(ExternalLoadTest.waitForProbe("AsyncFunctionBean.created"));

        container.unload(List.of(configuration));

        assertNull(container.getDependency(functionBean, "function"));
        assertFalse(container.hasDependecy(AsyncComponent.class, "asyncFunction"));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
    }

    @Test
    @DisplayName("lotes incrementais enxergam os externos já carregados")
    void incrementalBatchesSeePreviousExternals() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        Class<?> dependentService = module.load(ExternalFixtures.DEPENDENT_SERVICE);

        container.loadExternal(List.of(baseService));
        container.loadExternal(List.of(dependentService));

        Object dependent = container.getDependency(dependentService);
        assertEquals("dependent:base", ContainerFixture.invoke(dependent, "describe"));

        assertThrows(
                ExternalDependencyInUseException.class,
                () -> container.unload(List.of(baseService))
        );

        container.unload(List.of(dependentService));
        container.unload(List.of(baseService));

        assertFalse(container.hasDependecy(baseService));
        assertFalse(container.hasDependecy(dependentService));
        assertEquals(List.of("DependentService.destroy", "BaseService.destroy"),
                Probe.events().stream().filter(event -> event.endsWith(".destroy")).toList());
    }

    @Test
    @DisplayName("classe já registrada pelo container principal não é recriada nem removida")
    void mainComponentPassedAsExternalIsPreserved() throws Exception {
        dtm.di.testsupport.MainCounter before = container.getDependency(dtm.di.testsupport.MainCounter.class);

        container.loadExternal(List.of(dtm.di.testsupport.MainCounter.class));

        assertEquals(before, container.getDependency(dtm.di.testsupport.MainCounter.class));

        container.unload(List.of(dtm.di.testsupport.MainCounter.class));

        assertNotNull(container.getDependency(dtm.di.testsupport.MainCounter.class));
        assertTrue(container.hasDependecy(dtm.di.testsupport.MainCounter.class));
    }

    @Test
    @DisplayName("carregamentos concorrentes da mesma classe criam um único registro")
    void concurrentLoadOfTheSameClass() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);

        runConcurrently(8, () -> container.loadExternal(List.of(baseService)));

        assertEquals(1, Probe.count("BaseService.post"));
        assertEquals(1, ContainerFixture.externalRegistrationsOf(container).size());
        assertNotNull(container.getDependency(baseService));
    }

    @Test
    @DisplayName("cargas e descargas concorrentes de conjuntos disjuntos não interferem entre si")
    void concurrentLoadAndUnloadOfDisjointSets() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        Class<?> activeProfile = module.load(ExternalFixtures.ACTIVE_PROFILE_SERVICE);
        Class<?> valueService = module.load(ExternalFixtures.VALUE_SERVICE);

        container.loadExternal(List.of(baseService));

        List<Runnable> operations = new ArrayList<>();
        operations.add(() -> runQuietly(() -> container.loadExternal(List.of(activeProfile))));
        operations.add(() -> runQuietly(() -> container.loadExternal(List.of(valueService))));
        operations.add(() -> runQuietly(() -> container.unload(List.of(baseService))));

        runConcurrently(operations);

        assertFalse(container.hasDependecy(baseService));
        assertNotNull(container.getDependency(activeProfile));
        assertNotNull(container.getDependency(valueService));
        assertEquals(2, ContainerFixture.externalRegistrationsOf(container).size());
        assertTrue(container.isLoaded());
    }

    private void runConcurrently(int threads, ThrowingRunnable action) throws Exception {
        List<Runnable> operations = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            operations.add(() -> runQuietly(action));
        }
        runConcurrently(operations);
    }

    private void runConcurrently(List<Runnable> operations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(operations.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            for (Runnable operation : operations) {
                executor.execute(() -> {
                    try {
                        start.await();
                        operation.run();
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "operacoes concorrentes nao finalizaram");
        } finally {
            executor.shutdownNow();
        }

        if (failure.get() != null) {
            throw new AssertionError("falha em operacao concorrente", failure.get());
        }
    }

    private static void runQuietly(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
