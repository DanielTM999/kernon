package dtm.di.core;

import dtm.di.event.EventPublisher;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.MainPingEvent;
import dtm.di.testsupport.MainPrototypeListener;
import dtm.di.testsupport.Probe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrototypeListenerPolicyTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() {
        Probe.reset();
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. a politica padrao e SKIP")
    void defaultPolicyIsSkip() throws Exception {
        container = ContainerFixture.newLoadedContainer("configuration-settings");

        assertEquals(PrototypeListenerPolicy.SKIP, ContainerFixture.prototypeListenerPolicyOf(container));
    }

    @Test
    @DisplayName("2. a politica declarada nos settings e aplicada")
    void appliesPolicyFromSettings() throws Exception {
        container = ContainerFixture.newLoadedContainer("prototype-listener-register");

        assertEquals(PrototypeListenerPolicy.REGISTER, ContainerFixture.prototypeListenerPolicyOf(container));
    }

    @Test
    @DisplayName("3. a configuracao programatica tem precedencia sobre os settings")
    void programmaticPolicyTakesPrecedence() throws Exception {
        container = ContainerFixture.newContainer("prototype-listener-register");
        container.setPrototypeListenerPolicy(PrototypeListenerPolicy.SKIP_SILENT);
        container.load();

        assertEquals(PrototypeListenerPolicy.SKIP_SILENT, ContainerFixture.prototypeListenerPolicyOf(container));
    }

    @Test
    @DisplayName("4. setter com null seleciona o padrao SKIP")
    void programmaticNullSelectsDefault() throws Exception {
        container = ContainerFixture.newContainer("prototype-listener-register");
        container.setPrototypeListenerPolicy(null);
        container.load();

        assertEquals(PrototypeListenerPolicy.SKIP, ContainerFixture.prototypeListenerPolicyOf(container));
    }

    @Test
    @DisplayName("5. valor invalido nos settings volta para SKIP")
    void invalidDeclarativePolicyFallsBackToSkip() throws Exception {
        container = ContainerFixture.newLoadedContainer("prototype-listener-invalid");

        assertEquals(PrototypeListenerPolicy.SKIP, ContainerFixture.prototypeListenerPolicyOf(container));
    }

    @Test
    @DisplayName("6. SKIP nao entrega evento a nenhuma instancia")
    void skipDeliversToNobody() throws Exception {
        container = ContainerFixture.newLoadedContainer("test");

        publishPing();

        assertTrue(Probe.events().stream().noneMatch(event -> event.startsWith("MainPrototypeListener:p:")));
    }

    @Test
    @DisplayName("7. REGISTER entrega o evento a uma instancia dedicada do scan")
    void registerDeliversToDedicatedInstance() throws Exception {
        container = ContainerFixture.newContainer("test");
        container.setPrototypeListenerPolicy(PrototypeListenerPolicy.REGISTER);
        container.load();

        MainPrototypeListener resolved = container.getDependency(MainPrototypeListener.class);
        publishPing();

        assertTrue(
                Probe.events().stream().anyMatch(event -> event.startsWith("MainPrototypeListener:p:")),
                "a instancia dedicada do scan deve receber o evento"
        );
        assertEquals(0, Probe.count("MainPrototypeListener:p:" + System.identityHashCode(resolved)));
    }

    private void publishPing() {
        container.getDependency(EventPublisher.class).publish(new MainPingEvent("p"));
    }
}
