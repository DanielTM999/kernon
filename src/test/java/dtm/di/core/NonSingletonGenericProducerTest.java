package dtm.di.core;

import dtm.di.exceptions.UnloadError;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.FooPayload;
import dtm.di.testsupport.GenericProcessor;
import dtm.di.testsupport.NonSingletonGenericConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonSingletonGenericProducerTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("non-singleton-generic");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. produtor nao-singleton e indexado pelo argumento de tipo declarado")
    void nonSingletonProducerIsIndexedByGenericArgument() {
        assertTrue(ContainerFixture.genericIndexOf(container)
                .containsKey(GenericProcessor.class.getName() + "<" + FooPayload.class.getName() + ">"));
    }

    @Test
    @DisplayName("3. produtor nao-singleton que devolve lambda falha no registro")
    void nonSingletonLambdaProducerFailsRegistration() {
        ContainerFixture.dispose(container);
        container = null;

        UnloadError error = assertThrows(
                UnloadError.class,
                () -> ContainerFixture.newLoadedContainer("non-singleton-lambda")
        );

        assertTrue(exceptionMessages(error).contains("construtor vazio"));
    }

    private String exceptionMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            messages.append(current.getMessage()).append(System.lineSeparator());
            current = current.getCause();
        }
        return messages.toString();
    }

    @Test
    @DisplayName("2. a injecao generica resolve o produtor nao-singleton")
    void genericInjectionResolvesNonSingletonProducer() {
        NonSingletonGenericConsumer consumer = container.getDependency(NonSingletonGenericConsumer.class);

        assertNotNull(consumer);
        assertEquals("prototype-foo", consumer.processor().describe());
    }
}
