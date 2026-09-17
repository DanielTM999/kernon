package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.AsyncPayload;
import dtm.di.testsupport.AsyncQualifierConstructorConsumer;
import dtm.di.testsupport.AsyncQualifierFieldConsumer;
import dtm.di.testsupport.ContainerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AsyncQualifierParameterTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("async-qualifier");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. qualifier em parametro de construtor e respeitado")
    void constructorParameterQualifierIsHonored() throws Exception {
        AsyncQualifierConstructorConsumer consumer = container.getDependency(AsyncQualifierConstructorConsumer.class);

        assertNotNull(consumer);
        assertNotNull(consumer.special());
        assertEquals("special-payload", await(consumer.special()));
    }

    @Test
    @DisplayName("2. parametro sem qualifier continua no registro default")
    void constructorParameterWithoutQualifierUsesDefault() throws Exception {
        AsyncQualifierConstructorConsumer consumer = container.getDependency(AsyncQualifierConstructorConsumer.class);

        assertEquals("default-payload", await(consumer.byDefault()));
    }

    @Test
    @DisplayName("3. qualifier em campo continua funcionando")
    void fieldQualifierStillWorks() throws Exception {
        AsyncQualifierFieldConsumer consumer = container.getDependency(AsyncQualifierFieldConsumer.class);

        assertNotNull(consumer);
        assertEquals("special-payload", await(consumer.special()));
        assertEquals("default-payload", await(consumer.byDefault()));
    }

    @Test
    @DisplayName("4. acesso programatico por qualifier resolve o mesmo bean")
    void programmaticLookupMatches() throws Exception {
        assertEquals(
                "special-payload",
                container.getDependencyAsync(AsyncPayload.class, "special", true).getAsync().await().value()
        );
    }

    private static String await(dtm.di.prototypes.async.AsyncComponent<AsyncPayload> component) throws Exception {
        return component.getAsync().await().value();
    }
}
