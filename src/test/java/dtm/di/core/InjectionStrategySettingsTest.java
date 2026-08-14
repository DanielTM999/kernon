package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InjectionStrategySettingsTest {

    private DependencyContainerStorage container;

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    void appliesInjectionStrategyFromSettings() throws Exception {
        container = ContainerFixture.newContainer("injection-parallel");

        container.load();

        assertEquals(InjectionStrategy.PARALLEL, ContainerFixture.injectionStrategyOf(container));
    }

    @Test
    void programmaticStrategyTakesPrecedenceOverSettings() throws Exception {
        container = ContainerFixture.newContainer("configuration-settings");
        container.setInjectionStrategy(InjectionStrategy.PARALLEL);

        container.load();

        assertEquals(InjectionStrategy.PARALLEL, ContainerFixture.injectionStrategyOf(container));
    }

    @Test
    void appliesInjectionStrategyFromAppSettingsBean() throws Exception {
        container = ContainerFixture.newContainer("configuration-settings");

        container.load();

        assertEquals(InjectionStrategy.SEQUENTIAL, ContainerFixture.injectionStrategyOf(container));
    }

    @Test
    void invalidDeclarativeStrategyFallsBackToAdaptive() throws Exception {
        container = ContainerFixture.newContainer("injection-invalid");

        container.load();

        assertEquals(InjectionStrategy.ADAPTIVE, ContainerFixture.injectionStrategyOf(container));
    }

    @Test
    void programmaticNullStillTakesPrecedenceAndSelectsAdaptive() throws Exception {
        container = ContainerFixture.newContainer("injection-parallel");
        container.setInjectionStrategy(null);

        container.load();

        assertEquals(InjectionStrategy.ADAPTIVE, ContainerFixture.injectionStrategyOf(container));
    }

}
