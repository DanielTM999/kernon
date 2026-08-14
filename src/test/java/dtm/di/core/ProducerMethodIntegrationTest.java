package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.exceptions.UnloadError;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.Probe;
import dtm.di.testsupport.ProducerMethodConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProducerMethodIntegrationTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() {
        Probe.reset();
        ProducerMethodConfiguration.resetAsyncProducer();
    }

    @AfterEach
    void tearDown() {
        ProducerMethodConfiguration.releaseAsyncProducer();
        ContainerFixture.dispose(container);
    }

    @Test
    void initialLoadFiltersProfileAndDoesNotAwaitAsyncProducer() throws Exception {
        container = ContainerFixture.newLoadedContainer("producer-integration");

        ProducerMethodConfiguration.DependentBean dependent =
                container.getDependency(ProducerMethodConfiguration.DependentBean.class);

        assertNotNull(dependent);
        assertTrue(ProducerMethodConfiguration.awaitStarted());
        ProducerMethodConfiguration.SecondaryAsyncBean secondary = container.getDependencyAsync(
                ProducerMethodConfiguration.SecondaryAsyncBean.class,
                true
        ).getAsync().await();
        List<String> events = Probe.events();
        assertEquals(0, Probe.count("MainAsyncProducer.completed"));
        assertEquals(1, Probe.count("SecondaryAsyncProducer.completed"));
        assertEquals(1, Probe.count("MainAsyncProducer.dependent"));
        assertFalse(container.hasDependecy(ProducerMethodConfiguration.InactiveBean.class));
        assertEquals(0, Probe.count("MainProfileProducer.inactive"));
        assertTrue(events.stream().anyMatch(event -> event.startsWith("MainAsyncProducer.thread:MainExecutor-Worker-")));

        ProducerMethodConfiguration.releaseAsyncProducer();
        ProducerMethodConfiguration.AsyncBean asyncBean = dependent.asyncBean().getAsync().await();

        assertEquals("async", asyncBean.value());
        assertEquals("secondary", secondary.value());
        assertEquals(1, Probe.count("MainAsyncProducer.completed"));
    }

    @Test
    void directDependencyOnAsyncProducerFailsWithActionableMessage() {
        UnloadError error = assertThrows(
                UnloadError.class,
                () -> container = ContainerFixture.newLoadedContainer("invalid-async-producer-dependency")
        );

        assertTrue(exceptionMessages(error).contains("AsyncComponent<ProducedBean>"));
    }

    private String exceptionMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }
}
