package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.GenericOrderConsumer;
import dtm.di.testsupport.IntegerProcessor;
import dtm.di.testsupport.PrimaryProcessor;
import dtm.di.testsupport.QualifiedFooProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GenericResolutionOrderTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("generics-order");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. qualifier explicito vence o match generico")
    void explicitQualifierWins() {
        GenericOrderConsumer consumer = container.getDependency(GenericOrderConsumer.class);

        assertNotNull(consumer);
        assertInstanceOf(QualifiedFooProcessor.class, consumer.explicitlyQualified());
    }

    @Test
    @DisplayName("2. match generico exato vence o bean @Primary")
    void genericMatchWinsOverPrimary() {
        GenericOrderConsumer consumer = container.getDependency(GenericOrderConsumer.class);

        assertInstanceOf(IntegerProcessor.class, consumer.byGenericArgument());
    }

    @Test
    @DisplayName("3. injecao crua continua resolvendo pelo bean @Primary")
    void rawInjectionFallsBackToPrimary() {
        GenericOrderConsumer consumer = container.getDependency(GenericOrderConsumer.class);

        assertInstanceOf(PrimaryProcessor.class, consumer.rawProcessor());
    }
}
