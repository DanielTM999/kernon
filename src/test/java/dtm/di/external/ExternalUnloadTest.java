package dtm.di.external;

import dtm.di.event.EventPublisher;
import dtm.di.exceptions.ExternalDependencyInUseException;
import dtm.di.prototypes.async.AsyncComponent;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalFixtures;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.MainCounter;
import dtm.di.testsupport.Probe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalUnloadTest {

    private static ExternalModule module;

    private DependencyContainerStorage container;

    @BeforeAll
    static void compileModule() {
        module = ExternalModule.compile("unload", ExternalFixtures.sources());
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
    @DisplayName("15. @PreDestroy roda exatamente uma vez mesmo com vários slots")
    void preDestroyRunsOnce() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        container.loadExternal(List.of(baseService));

        container.unload(List.of(baseService));

        assertEquals(1, Probe.count("BaseService.destroy"));
        assertFalse(container.hasDependecy(baseService));
        assertFalse(container.hasDependecy(module.load(ExternalFixtures.GREETER)));
    }

    @Test
    @DisplayName("16. destruição segue a ordem inversa do grafo")
    void destructionFollowsReverseGraphOrder() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.DEPENDENT_SERVICE);
        container.loadExternal(classes);

        container.unload(classes);

        List<String> destroyed = Probe.events().stream()
                .filter(event -> event.endsWith(".destroy"))
                .toList();

        assertEquals(List.of("DependentService.destroy", "BaseService.destroy"), destroyed);
    }

    @Test
    @DisplayName("19 e 21. unload de subconjunto preserva os demais externos")
    void unloadSubset() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.ACTIVE_PROFILE_SERVICE);
        container.loadExternal(classes);

        container.unload(List.of(classes.get(1)));

        assertFalse(container.hasDependecy(classes.get(1)));
        assertNotNull(container.getDependency(classes.get(0)));
        assertEquals(1, ContainerFixture.externalRegistrationsOf(container).size());
    }

    @Test
    @DisplayName("20. container principal permanece intacto após unload externo")
    void mainContainerIsPreserved() throws Exception {
        Class<?> mainAware = module.load(ExternalFixtures.MAIN_AWARE);
        MainCounter before = container.getDependency(MainCounter.class);

        container.loadExternal(List.of(mainAware));
        container.unload(List.of(mainAware));

        assertTrue(container.isLoaded());
        assertSame(before, container.getDependency(MainCounter.class));
        assertFalse(container.hasDependecy(mainAware));
    }

    @Test
    @DisplayName("22. unload é rejeitado quando existe dependente externo ativo")
    void unloadRejectedWhenDependentIsActive() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.DEPENDENT_SERVICE);
        container.loadExternal(classes);

        ExternalDependencyInUseException error = assertThrows(
                ExternalDependencyInUseException.class,
                () -> container.unload(List.of(classes.get(0)))
        );

        assertTrue(error.getMessage().contains(ExternalFixtures.BASE_SERVICE));
        assertTrue(error.getMessage().contains(ExternalFixtures.DEPENDENT_SERVICE));
        assertTrue(error.getDependents().get(classes.get(0)).contains(classes.get(1)));

        assertNotNull(container.getDependency(classes.get(0)));
        assertNotNull(container.getDependency(classes.get(1)));
        assertEquals(0, Probe.count("BaseService.destroy"));

        container.unload(classes);

        assertFalse(container.hasDependecy(classes.get(0)));
        assertFalse(container.hasDependecy(classes.get(1)));
    }

    @Test
    @DisplayName("23. remoção seletiva por qualifier")
    void selectiveQualifierRemoval() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.ALPHA_SPEAKER, ExternalFixtures.BETA_SPEAKER);
        container.loadExternal(classes);

        Class<?> speaker = module.load(ExternalFixtures.SPEAKER);

        container.unload(List.of(classes.get(0)));

        assertNull(container.getDependency(speaker, "alpha"));
        assertNotNull(container.getDependency(speaker, "beta"));
        assertEquals("beta", ContainerFixture.invoke(container.getDependency(speaker, "beta"), "speak"));
        assertEquals(1, container.getDependencyList(speaker).size());
    }

    @Test
    @DisplayName("24. remoção seletiva de @Primary limpa o índice")
    void selectivePrimaryRemoval() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.PRIMARY_REPORTER, ExternalFixtures.FALLBACK_REPORTER);
        container.loadExternal(classes);

        Class<?> reporter = module.load(ExternalFixtures.REPORTER);
        assertTrue(ContainerFixture.primaryIndexOf(container).containsKey(reporter));

        container.unload(List.of(classes.get(0)));

        assertFalse(ContainerFixture.primaryIndexOf(container).containsKey(reporter));
        assertFalse(ContainerFixture.primaryIndexOf(container).containsKey(classes.get(0)));
        assertNotNull(container.getDependency(reporter, "fallback"));
    }

    @Test
    @DisplayName("25. listeners externos são removidos do EventPublisher")
    void eventListenersAreRemoved() throws Exception {
        Class<?> eventfulService = module.load(ExternalFixtures.EVENTFUL_SERVICE);
        Class<?> pingEvent = module.load(ExternalFixtures.PING_EVENT);

        container.loadExternal(List.of(eventfulService));

        EventPublisher publisher = container.getDependency(EventPublisher.class);
        publisher.publish(pingEvent.getConstructor(String.class).newInstance("um"));

        assertEquals(1, Probe.count("EventfulService.onPing:um"));

        container.unload(List.of(eventfulService));
        publisher.publish(pingEvent.getConstructor(String.class).newInstance("dois"));

        assertEquals(0, Probe.count("EventfulService.onPing:dois"));
    }

    @Test
    @DisplayName("26. ReflectionCache é limpo apenas para as classes descarregadas")
    void reflectionCacheIsClearedSelectively() throws Exception {
        try (ExternalModule cacheModule = ExternalModule.compile("reflection", ExternalFixtures.sources())) {
            List<Class<?>> classes = cacheModule.load(
                    ExternalFixtures.BASE_SERVICE,
                    ExternalFixtures.ACTIVE_PROFILE_SERVICE
            );

            container.loadExternal(classes);

            assertTrue(ContainerFixture.isReflectionCached(classes.get(0)));
            assertTrue(ContainerFixture.isReflectionCached(classes.get(1)));

            container.unload(List.of(classes.get(0)));

            assertFalse(ContainerFixture.isReflectionCached(classes.get(0)));
            assertTrue(ContainerFixture.isReflectionCached(classes.get(1)));
        }
    }

    @Test
    @DisplayName("27. ProxyFactory é limpo apenas para as classes descarregadas")
    void proxyCacheIsClearedSelectively() throws Exception {
        try (ExternalModule cacheModule = ExternalModule.compile("proxy", ExternalFixtures.sources())) {
            List<Class<?>> classes = cacheModule.load(
                    ExternalFixtures.BASE_SERVICE,
                    ExternalFixtures.ACTIVE_PROFILE_SERVICE
            );

            container.enableAOP();
            container.loadExternal(classes);

            assertTrue(ContainerFixture.isProxyCached(classes.get(0)));
            assertTrue(ContainerFixture.isProxyCached(classes.get(1)));

            container.unload(List.of(classes.get(0)));

            assertFalse(ContainerFixture.isProxyCached(classes.get(0)));
            assertTrue(ContainerFixture.isProxyCached(classes.get(1)));
        } finally {
            container.disableAOP();
        }
    }

    @Test
    @DisplayName("28. unload repetido e de classes desconhecidas é no-op")
    void unloadIsIdempotent() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        container.loadExternal(List.of(baseService));

        container.unload(List.of(baseService));
        container.unload(List.of(baseService));
        container.unload(List.of(module.load(ExternalFixtures.PLAIN_CLASS)));

        assertEquals(1, Probe.count("BaseService.destroy"));
        assertTrue(container.isLoaded());
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
    }

    @Test
    @DisplayName("beans de uma @Configuration externa pertencem à configuração")
    void configurationOwnsProducedBeans() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.EXTERNAL_CONFIGURATION);
        container.loadExternal(classes);

        Class<?> configBean = module.load(ExternalFixtures.CONFIG_BEAN);
        Class<?> componentAwareBean = module.load(ExternalFixtures.COMPONENT_AWARE_BEAN);

        assertNotNull(container.getDependency(configBean));
        assertNotNull(container.getDependency(componentAwareBean));

        container.unload(List.of(classes.get(1)));

        assertFalse(container.hasDependecy(configBean));
        assertFalse(container.hasDependecy(componentAwareBean));
        assertEquals(1, Probe.count("ComponentAwareBean.destroy"));
        assertNotNull(container.getDependency(classes.get(0)));
    }

    @Test
    @DisplayName("prototype externo perde a fábrica registrada no unload")
    void prototypeFactoryIsRemoved() throws Exception {
        Class<?> prototype = module.load(ExternalFixtures.PROTOTYPE_SERVICE);
        container.loadExternal(List.of(prototype));

        assertNotNull(container.getDependency(prototype));

        container.unload(List.of(prototype));

        assertFalse(container.hasDependecy(prototype));
        assertNull(container.getDependency(prototype));
    }

    @Test
    @DisplayName("33. unload global após carga externa limpa tudo uma única vez")
    void globalUnloadAfterExternalLoad() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.DEPENDENT_SERVICE);
        container.loadExternal(classes);

        container.unload();

        assertFalse(container.isLoaded());
        assertEquals(1, Probe.count("BaseService.destroy"));
        assertEquals(1, Probe.count("DependentService.destroy"));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
        assertTrue(ContainerFixture.dependencyContainerOf(container).isEmpty());
    }

    @Test
    @DisplayName("35. tarefa async externa é cancelada e destruída no unload")
    void asyncTaskIsCancelledOnUnload() throws Exception {
        Class<?> asyncService = module.load(ExternalFixtures.ASYNC_SERVICE);
        container.loadExternal(List.of(asyncService));

        assertTrue(ExternalLoadTest.waitForProbe("AsyncService.created"));
        Thread.sleep(200);

        container.unload(List.of(asyncService));

        assertFalse(container.hasDependecy(AsyncComponent.class, "default"));
        assertEquals(1, Probe.count("AsyncService.destroy"));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
    }

    @Test
    @DisplayName("slots removidos não afetam entradas de outra dependência com o mesmo qualifier")
    void removalKeepsOtherRegistrations() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.ACTIVE_PROFILE_SERVICE);
        container.loadExternal(classes);

        Map<Class<?>, ?> registrations = ContainerFixture.externalRegistrationsOf(container);
        assertEquals(2, registrations.size());

        container.unload(List.of(classes.get(0)));

        assertFalse(ContainerFixture.dependencyContainerOf(container).containsKey(classes.get(0)));
        assertTrue(ContainerFixture.dependencyContainerOf(container).containsKey(classes.get(1)));
    }
}
