package dtm.di.external;

import dtm.di.event.EventPublisher;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalFixtures;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.MainPingEvent;
import dtm.di.testsupport.MainPrototypeListener;
import dtm.di.testsupport.Probe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalEventListenerTest {

    private static final String LISTENER_WITHOUT_EVENT = "ext.ListenerWithoutEvent";
    private static final String EVENT_WITHOUT_LISTENER = "ext.EventWithoutListener";
    private static final String PROTOTYPE_LISTENER = "ext.PrototypeListener";

    private static ExternalModule module;

    private DependencyContainerStorage container;

    @BeforeAll
    static void compileModule() {
        Map<String, String> sources = new LinkedHashMap<>(ExternalFixtures.sources());

        sources.put(LISTENER_WITHOUT_EVENT, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Singleton;
                import dtm.di.annotations.event.EventListener;
                import dtm.di.testsupport.Probe;

                @Singleton
                @Component
                public class ListenerWithoutEvent {

                    @EventListener
                    public void onPing(PingEvent event) {
                        Probe.record("ListenerWithoutEvent:" + event.message());
                    }
                }
                """);

        sources.put(EVENT_WITHOUT_LISTENER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Singleton;
                import dtm.di.annotations.event.Event;

                @Singleton
                @Component
                @Event
                public class EventWithoutListener {
                    public String value() {
                        return "sem-listener";
                    }
                }
                """);

        sources.put(PROTOTYPE_LISTENER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.event.Event;
                import dtm.di.annotations.event.EventListener;
                import dtm.di.testsupport.Probe;

                @Component
                @Event
                public class PrototypeListener {

                    @EventListener
                    public void onPing(PingEvent event) {
                        Probe.record("PrototypeListener:" + event.message());
                    }
                }
                """);

        module = ExternalModule.compile("event-listener", sources);
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
    @DisplayName("componente externo com @EventListener recebe eventos sem precisar de @Event")
    void externalComponentWithListenerMethodsReceivesEvents() throws Exception {
        Class<?> withoutEvent = module.load(LISTENER_WITHOUT_EVENT);
        Class<?> withEvent = module.load(ExternalFixtures.EVENTFUL_SERVICE);

        container.loadExternal(List.of(withoutEvent, withEvent));
        publish("x");

        assertEquals(1, Probe.count("ListenerWithoutEvent:x"));
        assertEquals(1, Probe.count("EventfulService.onPing:x"));
    }

    @Test
    @DisplayName("listeners sem @Event também são removidos no unload")
    void listenersWithoutEventAnnotationAreRemovedOnUnload() throws Exception {
        Class<?> withoutEvent = module.load(LISTENER_WITHOUT_EVENT);

        container.loadExternal(List.of(withoutEvent));
        publish("antes");
        assertEquals(1, Probe.count("ListenerWithoutEvent:antes"));

        container.unload(List.of(withoutEvent));
        publish("depois");

        assertEquals(0, Probe.count("ListenerWithoutEvent:depois"));
        assertTrue(container.isLoaded());
    }

    @Test
    @DisplayName("componente externo com @Event mas sem método listener não quebra o carregamento")
    void externalComponentWithEventAndNoListenerMethods() throws Exception {
        Class<?> eventWithoutListener = module.load(EVENT_WITHOUT_LISTENER);

        container.loadExternal(List.of(eventWithoutListener));
        publish("y");

        assertEquals("sem-listener", ContainerFixture.invoke(container.getDependency(eventWithoutListener), "value"));

        container.unload(List.of(eventWithoutListener));
        assertTrue(container.isLoaded());
    }

    @Test
    @DisplayName("prototype externo não recebe eventos: nenhuma instância existe no carregamento")
    void externalPrototypeDoesNotReceiveEvents() throws Exception {
        Class<?> prototypeListener = module.load(PROTOTYPE_LISTENER);

        container.loadExternal(List.of(prototypeListener));

        Object first = container.getDependency(prototypeListener);
        Object second = container.getDependency(prototypeListener);
        assertNotSame(first, second);

        publish("p");

        assertEquals(0, Probe.count("PrototypeListener:p"));
    }

    @Test
    @DisplayName("no container principal um prototype listener recebe o evento em uma instância fantasma")
    void mainContainerPrototypeListenerReceivesOnAGhostInstance() {
        MainPrototypeListener obtido = container.getDependency(MainPrototypeListener.class);

        container.getDependency(EventPublisher.class).publish(new MainPingEvent("p"));

        assertEquals(0, Probe.count("MainPrototypeListener:p:" + System.identityHashCode(obtido)));
        assertTrue(
                Probe.events().stream().anyMatch(event -> event.startsWith("MainPrototypeListener:p:")),
                "o boot registra o listener em uma instância descartável"
        );
    }

    @Test
    @DisplayName("o container principal mantém a regra do boot: só beans com @Event são escaneados")
    void mainContainerKeepsBootRule() {
        EventPublisher publisher = container.getDependency(EventPublisher.class);
        publisher.publish(new MainPingEvent("m"));

        assertEquals(1, Probe.count("MainListenerWithEvent:m"));
        assertEquals(0, Probe.count("MainListenerWithoutEvent:m"));
    }

    private void publish(String message) throws Exception {
        Class<?> pingEvent = module.load(ExternalFixtures.PING_EVENT);
        container.getDependency(EventPublisher.class)
                .publish(pingEvent.getConstructor(String.class).newInstance(message));
    }
}
