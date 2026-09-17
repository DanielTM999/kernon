package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.BarPayload;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.FooPayload;
import dtm.di.testsupport.GenericProcessor;
import dtm.di.testsupport.GenericProducerConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericProducerResolutionTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("generics-producer");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. produtor lambda e indexado pelo tipo generico declarado no metodo")
    void lambdaProducerIsIndexedByDeclaredReturnType() {
        var index = ContainerFixture.genericIndexOf(container);

        assertTrue(index.containsKey(genericKey(FooPayload.class)));
        assertTrue(index.containsKey(genericKey(BarPayload.class)));
    }

    @Test
    @DisplayName("2. dois produtores genericos na mesma configuracao nao colidem")
    void producersDoNotCollide() {
        GenericProducerConsumer consumer = container.getDependency(GenericProducerConsumer.class);

        assertNotNull(consumer);
        assertEquals("produced-foo", consumer.fooProcessor().describe());
        assertEquals("produced-bar", consumer.barProcessor().describe());
    }

    private static String genericKey(Class<?> argument) {
        return GenericProcessor.class.getName() + "<" + argument.getName() + ">";
    }
}
