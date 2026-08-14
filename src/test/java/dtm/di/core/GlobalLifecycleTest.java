package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.MainPreDestroyProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalLifecycleTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("test");
        MainPreDestroyProbe.reset();
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("unload global executa @PreDestroy uma vez para singleton do container principal")
    void globalUnloadDestroysMainSingletonExactlyOnce() {
        assertNotNull(container.getDependency(MainPreDestroyProbe.class));

        container.unload();
        container.unload();

        assertEquals(1, MainPreDestroyProbe.destroyCount());
    }
}
