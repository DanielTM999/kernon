package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.FooProcessor;
import dtm.di.testsupport.GenericWrapperConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericWrapperResolutionTest {

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
    @DisplayName("1. LazyDependency de tipo generico resolve sem ClassCastException")
    void lazyDependencyResolvesGenericTarget() {
        GenericWrapperConsumer consumer = container.getDependency(GenericWrapperConsumer.class);

        assertNotNull(consumer);
        assertInstanceOf(FooProcessor.class, consumer.lazyFoo().get());
    }

    @Test
    @DisplayName("2. CompositeDependency filtra pelo argumento generico")
    void compositeDependencyFiltersByGenericArgument() {
        GenericWrapperConsumer consumer = container.getDependency(GenericWrapperConsumer.class);

        assertEquals(1, consumer.compositeFoo().getAsList().size());
        assertInstanceOf(FooProcessor.class, consumer.compositeFoo().getAsList().getFirst());
    }

    @Test
    @DisplayName("3. CompositeDependency com wildcard mantem todos os candidatos")
    void compositeDependencyWithWildcardKeepsEveryone() {
        GenericWrapperConsumer consumer = container.getDependency(GenericWrapperConsumer.class);

        assertTrue(consumer.compositeAll().getAsList().size() >= consumer.compositeFoo().getAsList().size());
    }

    @Test
    @DisplayName("4. List de tipo generico deixa de falhar em silencio")
    void beanListIsInjected() {
        GenericWrapperConsumer consumer = container.getDependency(GenericWrapperConsumer.class);

        assertNotNull(consumer.listFoo());
        assertEquals(1, consumer.listFoo().size());
        assertInstanceOf(FooProcessor.class, consumer.listFoo().getFirst());
    }

    @Test
    @DisplayName("5. AtomicReference continua embrulhando o bean resolvido")
    void atomicReferenceStillWraps() {
        GenericWrapperConsumer consumer = container.getDependency(GenericWrapperConsumer.class);

        assertNotNull(consumer.referenceFoo());
        assertInstanceOf(FooProcessor.class, consumer.referenceFoo().get());
    }
}
