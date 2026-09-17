package dtm.di.core;

import dtm.di.prototypes.TypeRef;
import dtm.di.prototypes.async.AsyncComponent;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.AsyncGenericConstructorConsumer;
import dtm.di.testsupport.AsyncGenericFieldConsumer;
import dtm.di.testsupport.AsyncGenericProducerConsumer;
import dtm.di.testsupport.AsyncStringProcessor;
import dtm.di.testsupport.BarPayload;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.FooPayload;
import dtm.di.testsupport.GenericProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AsyncGenericResolutionTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("generics-async");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. campo AsyncComponent generico resolve o produtor correspondente")
    void fieldResolvesByGenericArgument() throws Exception {
        AsyncGenericFieldConsumer consumer = container.getDependency(AsyncGenericFieldConsumer.class);

        assertNotNull(consumer);
        assertEquals("async-foo", await(consumer.foo()));
        assertEquals("async-bar", await(consumer.bar()));
    }

    @Test
    @DisplayName("2. parametro de construtor AsyncComponent generico resolve igual")
    void constructorResolvesByGenericArgument() throws Exception {
        AsyncGenericConstructorConsumer consumer = container.getDependency(AsyncGenericConstructorConsumer.class);

        assertNotNull(consumer);
        assertEquals("async-foo", await(consumer.foo()));
        assertEquals("async-bar", await(consumer.bar()));
    }

    @Test
    @DisplayName("3. dois produtores async do mesmo tipo cru coexistem no registro")
    void producersOfSameRawTypeCoexist() {
        var asyncSlot = ContainerFixture.dependencyContainerOf(container).get(AsyncComponent.class);

        assertNotNull(asyncSlot);
        assertEquals(
                3,
                asyncSlot.values().stream().filter(d -> GenericProcessor.class.isAssignableFrom(d.getDependencyClass())).count()
        );
    }

    @Test
    @DisplayName("4. classe @Async generica e injetavel pelo argumento de tipo")
    void asyncClassIsResolvedByGenericArgument() throws Exception {
        AsyncGenericFieldConsumer consumer = container.getDependency(AsyncGenericFieldConsumer.class);

        assertEquals("async-string-processor", await(consumer.fromAsyncClass()));
        assertInstanceOf(AsyncStringProcessor.class, consumer.fromAsyncClass().getAsync().await());
    }

    @Test
    @DisplayName("5. bean async nao vaza para o indice generico compartilhado")
    void asyncBeansDoNotLeakIntoSharedIndex() {
        assertFalse(ContainerFixture.genericIndexOf(container).containsKey(genericKey(FooPayload.class)));
        assertFalse(ContainerFixture.genericIndexOf(container).containsKey(genericKey(BarPayload.class)));
        assertFalse(ContainerFixture.dependencyContainerOf(container).containsKey(AsyncStringProcessor.class));
        assertFalse(ContainerFixture.dependencyContainerOf(container).containsKey(GenericProcessor.class));
    }

    @Test
    @DisplayName("6. acesso programatico por TypeRef resolve o mesmo bean da injecao")
    void typedLookupMatchesFieldInjection() throws Exception {
        AsyncGenericFieldConsumer consumer = container.getDependency(AsyncGenericFieldConsumer.class);

        AsyncComponent<GenericProcessor<FooPayload>> byType =
                container.getDependencyAsync(new TypeRef<GenericProcessor<FooPayload>>() {}, true);

        assertNotNull(byType);
        assertSame(consumer.foo().getAsync().await(), byType.getAsync().await());
        assertEquals("async-foo", await(byType));
    }

    @Test
    @DisplayName("7. TypeRef distingue os dois produtores do mesmo tipo cru")
    void typedLookupDistinguishesProducers() throws Exception {
        assertEquals(
                "async-bar",
                await(container.getDependencyAsync(new TypeRef<GenericProcessor<BarPayload>>() {}, true))
        );
    }

    @Test
    @DisplayName("8. produtor que recebe AsyncComponent generico e ligado ao produtor certo")
    void producerParameterResolvesByGenericArgument() throws Exception {
        AsyncGenericProducerConsumer consumer = container.getDependency(AsyncGenericProducerConsumer.class);

        assertNotNull(consumer);
        assertEquals("async-bar", await(consumer.bar()));
    }

    private static String await(AsyncComponent<? extends GenericProcessor<?>> component) throws Exception {
        return component.getAsync().await().describe();
    }

    private static String genericKey(Class<?> argument) {
        return GenericProcessor.class.getName() + "<" + argument.getName() + ">";
    }
}
