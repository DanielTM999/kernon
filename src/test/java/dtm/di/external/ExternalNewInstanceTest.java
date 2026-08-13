package dtm.di.external;

import dtm.di.event.EventPublisher;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalFixtures;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.NewInstanceTarget;
import dtm.di.testsupport.Probe;
import dtm.di.testsupport.SharedGreeter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExternalNewInstanceTest {

    private static ExternalModule module;

    private DependencyContainerStorage container;

    @BeforeAll
    static void compileModule() {
        module = ExternalModule.compile("new-instance", ExternalFixtures.sources());
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
    @DisplayName("newInstance injeta bean externo por interface compartilhada")
    void newInstanceReceivesExternalBeanThroughSharedInterface() throws Exception {
        Class<?> sharedImpl = module.load(ExternalFixtures.SHARED_GREETER_IMPL);
        container.loadExternal(List.of(sharedImpl));

        NewInstanceTarget target = container.newInstance(NewInstanceTarget.class);

        assertNotNull(target.greeter());
        assertEquals("external-greeter", target.describe());
        assertSame(container.getDependency(SharedGreeter.class), target.greeter());
        assertNotNull(target.counter());
    }

    @Test
    @DisplayName("newInstance de classe externa recebe outro bean externo e não é registrado")
    void newInstanceOfExternalClassReceivesExternalBean() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        Class<?> detachedConsumer = module.load(ExternalFixtures.DETACHED_CONSUMER);

        container.loadExternal(List.of(baseService));

        Object first = container.newInstance(detachedConsumer);
        Object second = container.newInstance(detachedConsumer);

        assertEquals("detached:base", ContainerFixture.invoke(first, "describe"));
        assertNotSame(first, second);
        assertFalse(container.hasDependecy(detachedConsumer));
        assertFalse(ContainerFixture.externalRegistrationsOf(container).containsKey(detachedConsumer));
    }

    @Test
    @DisplayName("após o unload o newInstance deixa de resolver o bean externo")
    void newInstanceStopsResolvingAfterUnload() throws Exception {
        Class<?> sharedImpl = module.load(ExternalFixtures.SHARED_GREETER_IMPL);
        container.loadExternal(List.of(sharedImpl));

        assertEquals("external-greeter", container.newInstance(NewInstanceTarget.class).describe());

        container.unload(List.of(sharedImpl));

        NewInstanceTarget target = container.newInstance(NewInstanceTarget.class);

        assertNull(target.greeter());
        assertEquals("sem-externo", target.describe());
        assertNotNull(target.counter());
    }

    @Test
    @DisplayName("listeners de instância criada por newInstance de classe externa somem no unload")
    void newInstanceEventListenersAreRemovedOnUnload() throws Exception {
        Class<?> eventful = module.load(ExternalFixtures.EVENTFUL_SERVICE);
        Class<?> pingEvent = module.load(ExternalFixtures.PING_EVENT);

        container.loadExternal(List.of(eventful));
        container.newInstance(eventful);

        EventPublisher publisher = container.getDependency(EventPublisher.class);
        publisher.publish(pingEvent.getConstructor(String.class).newInstance("antes"));

        assertEquals(2, Probe.count("EventfulService.onPing:antes"));

        container.unload(List.of(eventful));
        publisher.publish(pingEvent.getConstructor(String.class).newInstance("depois"));

        assertEquals(0, Probe.count("EventfulService.onPing:depois"));
    }

    @Test
    @DisplayName("instância criada por newInstance não é destruída pelo unload externo")
    void newInstanceIsNotOwnedByTheExternalRegistration() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        container.loadExternal(List.of(baseService));

        Object detached = container.newInstance(baseService);
        assertNotNull(detached);
        assertNotSame(container.getDependency(baseService), detached);

        container.unload(List.of(baseService));

        assertEquals(1, Probe.count("BaseService.destroy"));
    }
}
