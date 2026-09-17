package dtm.di.core;

import dtm.di.annotations.Component;
import dtm.di.annotations.Primary;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.Service;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProducerQualifierResolutionTest {

    static class Cases {
        @Component @Qualifier("wanted") public String componentPlusQualifier() { return ""; }
        @Service @Qualifier("wanted") public String servicePlusQualifier() { return ""; }
        @Component(qualifier = "fromAttribute") @Qualifier("wanted") public String qualifierBeatsAttribute() { return ""; }
        @Component(qualifier = "fromAttribute") public String attributeOnly() { return ""; }
        @Component @Primary public String componentPlusPrimary() { return ""; }
        @Service @Primary public String servicePlusPrimary() { return ""; }
        @Component(qualifier = "fromAttribute") @Primary public String attributeBeatsPrimary() { return ""; }
        @Component(qualifier = "default") @Primary public String explicitDefaultIsNotSpecified() { return ""; }
        @Component public String nothing() { return ""; }
    }

    private DependencyContainerStorage container;
    private Method resolve;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newContainer("producer-qualifier");
        resolve = DependencyContainerStorage.class.getDeclaredMethod("getQualifierName", Method.class);
        resolve.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. @Qualifier e a fonte da verdade em produtor @Component")
    void qualifierWinsOverComponent() throws Exception {
        assertEquals("wanted", qualifierOf("componentPlusQualifier"));
    }

    @Test
    @DisplayName("2. @Qualifier e a fonte da verdade em produtor @Service")
    void qualifierWinsOverService() throws Exception {
        assertEquals("wanted", qualifierOf("servicePlusQualifier"));
    }

    @Test
    @DisplayName("3. @Qualifier vence o atributo da anotacao produtora")
    void qualifierWinsOverAttribute() throws Exception {
        assertEquals("wanted", qualifierOf("qualifierBeatsAttribute"));
    }

    @Test
    @DisplayName("4. sem @Qualifier, vale o atributo da anotacao produtora")
    void attributeIsUsedWhenQualifierAbsent() throws Exception {
        assertEquals("fromAttribute", qualifierOf("attributeOnly"));
    }

    @Test
    @DisplayName("5. @Primary e consultado quando nenhum qualifier foi especificado")
    void primaryIsHonoredOnComponentProducer() throws Exception {
        assertTrue(qualifierOf("componentPlusPrimary").startsWith("$primary$:"));
        assertTrue(qualifierOf("servicePlusPrimary").startsWith("$primary$:"));
    }

    @Test
    @DisplayName("6. qualifier explicito no atributo vence @Primary")
    void attributeWinsOverPrimary() throws Exception {
        assertEquals("fromAttribute", qualifierOf("attributeBeatsPrimary"));
    }

    @Test
    @DisplayName("7. qualifier 'default' explicito conta como nao especificado")
    void explicitDefaultCountsAsUnspecified() throws Exception {
        assertTrue(qualifierOf("explicitDefaultIsNotSpecified").startsWith("$primary$:"));
    }

    @Test
    @DisplayName("8. sem nada, o qualifier continua sendo default")
    void fallsBackToDefault() throws Exception {
        assertEquals("default", qualifierOf("nothing"));
    }

    private String qualifierOf(String methodName) throws Exception {
        return (String) resolve.invoke(container, Cases.class.getDeclaredMethod(methodName));
    }
}
