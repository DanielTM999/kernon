package dtm.di.external;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalFixtures;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.Probe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalClassLoaderIsolationTest {

    private DependencyContainerStorage container;

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
    @DisplayName("classes externas não precisam ser visíveis ao classloader do Kernon")
    void externalClassesAreNotVisibleToTheContainerClassLoader() throws Exception {
        try (ExternalModule isolated = ExternalModule.compile("isolated", ExternalFixtures.sources())) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(ExternalFixtures.BASE_SERVICE));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName(ExternalFixtures.BASE_SERVICE, false, DependencyContainerStorage.class.getClassLoader())
            );

            List<Class<?>> classes = isolated.load(
                    ExternalFixtures.BASE_SERVICE,
                    ExternalFixtures.DEPENDENT_SERVICE,
                    ExternalFixtures.EXTERNAL_CONFIGURATION
            );

            container.loadExternal(classes);

            Object dependent = container.getDependency(classes.get(1));
            assertNotNull(dependent);
            assertEquals("dependent:base", ContainerFixture.invoke(dependent, "describe"));
            assertNotNull(container.getDependency(isolated.load(ExternalFixtures.CONFIG_BEAN)));

            container.unload(classes);

            assertFalse(container.hasDependecy(classes.get(0)));
            assertTrue(container.isLoaded());
        }
    }

    @Test
    @DisplayName("31. classes de mesmo nome em classloaders diferentes são registros distintos")
    void sameClassNameInDifferentClassLoaders() throws Exception {
        try (ExternalModule first = ExternalModule.compile("dup-first", ExternalFixtures.sources());
             ExternalModule second = ExternalModule.compile("dup-second", ExternalFixtures.sources())) {

            Class<?> firstService = first.load(ExternalFixtures.BASE_SERVICE);
            Class<?> secondService = second.load(ExternalFixtures.BASE_SERVICE);

            assertEquals(firstService.getName(), secondService.getName());
            assertNotSame(firstService, secondService);
            assertNotSame(firstService.getClassLoader(), secondService.getClassLoader());

            container.loadExternal(List.of(firstService, secondService));

            Object firstInstance = container.getDependency(firstService);
            Object secondInstance = container.getDependency(secondService);

            assertNotNull(firstInstance);
            assertNotNull(secondInstance);
            assertNotSame(firstInstance, secondInstance);
            assertEquals(2, ContainerFixture.externalRegistrationsOf(container).size());
            assertEquals(2, Probe.count("BaseService.post"));

            container.unload(List.of(firstService));

            assertFalse(container.hasDependecy(firstService));
            assertNotNull(container.getDependency(secondService));
            assertEquals(1, ContainerFixture.externalRegistrationsOf(container).size());
            assertEquals(1, Probe.count("BaseService.destroy"));
        }
    }

    @Test
    @DisplayName("classloader externo fica elegível para coleta após o unload (coleta não determinística, várias tentativas)")
    void externalClassLoaderBecomesCollectable() throws Exception {
        WeakReference<ClassLoader> loaderReference = loadAndUnloadIsolatedModule();

        assertTrue(
                awaitCollection(loaderReference, 50),
                "o classloader externo permaneceu acessível apos o unload"
        );
    }

    private WeakReference<ClassLoader> loadAndUnloadIsolatedModule() throws Exception {
        ExternalModule isolated = ExternalModule.compile("collectable", ExternalFixtures.sources());

        List<Class<?>> classes = new ArrayList<>(isolated.load(
                ExternalFixtures.BASE_SERVICE,
                ExternalFixtures.DEPENDENT_SERVICE,
                ExternalFixtures.PROTOTYPE_SERVICE
        ));

        container.loadExternal(classes);
        assertNotNull(container.getDependency(classes.get(1)));

        container.unload(classes);

        WeakReference<ClassLoader> reference = new WeakReference<>(isolated.classLoader());

        classes.clear();
        isolated.close();

        return reference;
    }

    private static boolean awaitCollection(WeakReference<?> reference, int attempts) throws InterruptedException {
        for (int attempt = 0; attempt < attempts && reference.get() != null; attempt++) {
            System.gc();
            allocatePressure();
            Thread.sleep(20);
        }

        return reference.get() == null;
    }

    private static void allocatePressure() {
        List<byte[]> ballast = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            ballast.add(new byte[512 * 1024]);
        }
        ballast.clear();
    }
}
