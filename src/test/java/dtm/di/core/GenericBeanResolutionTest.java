package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.BarPayload;
import dtm.di.testsupport.BarProcessor;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.FooPayload;
import dtm.di.testsupport.FooProcessor;
import dtm.di.testsupport.GenericConstructorConsumer;
import dtm.di.testsupport.GenericFieldConsumer;
import dtm.di.testsupport.GenericProcessor;
import dtm.di.testsupport.StringProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericBeanResolutionTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("generics");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. campo generico sem qualifier resolve a implementacao correspondente")
    void fieldInjectionResolvesByGenericArgument() {
        GenericFieldConsumer consumer = container.getDependency(GenericFieldConsumer.class);

        assertNotNull(consumer);
        assertInstanceOf(FooProcessor.class, consumer.fooProcessor());
        assertInstanceOf(BarProcessor.class, consumer.barProcessor());
    }

    @Test
    @DisplayName("2. parametro de construtor generico resolve a implementacao correspondente")
    void constructorInjectionResolvesByGenericArgument() {
        GenericConstructorConsumer consumer = container.getDependency(GenericConstructorConsumer.class);

        assertNotNull(consumer);
        assertInstanceOf(FooProcessor.class, consumer.fooProcessor());
        assertInstanceOf(BarProcessor.class, consumer.barProcessor());
    }

    @Test
    @DisplayName("3. substituicao de variavel de tipo pela superclasse tambem resolve")
    void inheritedTypeVariableIsResolved() {
        GenericFieldConsumer consumer = container.getDependency(GenericFieldConsumer.class);

        assertInstanceOf(StringProcessor.class, consumer.stringProcessor());
    }

    @Test
    @DisplayName("4. o indice generico registra as chaves totalmente resolvidas")
    void genericIndexIsPopulated() {
        var index = ContainerFixture.genericIndexOf(container);

        assertTrue(index.containsKey(genericKey(FooPayload.class)));
        assertTrue(index.containsKey(genericKey(BarPayload.class)));
        assertTrue(index.containsKey(GenericProcessor.class.getName() + "<java.lang.String>"));
    }

    @Test
    @DisplayName("5. as implementacoes continuam registradas sob a interface crua")
    void rawIndexStillHoldsEveryImplementation() {
        var registrations = ContainerFixture.dependencyContainerOf(container).get(GenericProcessor.class);

        assertNotNull(registrations);
        assertEquals("foo-processor", container.getDependency(FooProcessor.class).describe());
        assertEquals("bar-processor", container.getDependency(BarProcessor.class).describe());
    }

    private static String genericKey(Class<?> argument) {
        return GenericProcessor.class.getName() + "<" + argument.getName() + ">";
    }
}
