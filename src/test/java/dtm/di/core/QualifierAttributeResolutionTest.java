package dtm.di.core;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
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
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualifierAttributeResolutionTest {

    @Component(qualifier = "fromComponent")
    static class ComponentWithAttribute {}

    @Service(qualifier = "fromService")
    static class ServiceWithAttribute {}

    @Component(qualifier = "fromComponent")
    @Qualifier("wanted")
    static class QualifierBeatsAttribute {}

    @Component(qualifier = "fromComponent")
    @Primary
    static class AttributeBeatsPrimary {}

    @Component(qualifier = "default")
    @Primary
    static class ExplicitDefaultIsUnspecified {}

    @Component
    @Primary
    static class PrimaryOnly {}

    @Component
    static class Nothing {}

    static class ParameterCases {
        public void injectQualifier(@Inject(qualifier = "wanted") String value) {}
        public void qualifierBeatsInject(@Inject(qualifier = "fromInject") @Qualifier("wanted") String value) {}
        public void plain(String value) {}
    }

    private DependencyContainerStorage container;
    private Method resolveClass;
    private Method resolveParameter;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newContainer("qualifier-attribute");
        resolveClass = DependencyContainerStorage.class.getDeclaredMethod("getQualifierName", Class.class);
        resolveClass.setAccessible(true);
        resolveParameter = DependencyContainerStorage.class.getDeclaredMethod("getQualifierName", Parameter.class);
        resolveParameter.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. @Component(qualifier) e lido em classe")
    void componentAttributeOnClass() throws Exception {
        assertEquals("fromComponent", qualifierOf(ComponentWithAttribute.class));
    }

    @Test
    @DisplayName("2. @Service(qualifier) e lido em classe")
    void serviceAttributeOnClass() throws Exception {
        assertEquals("fromService", qualifierOf(ServiceWithAttribute.class));
    }

    @Test
    @DisplayName("3. @Qualifier vence o atributo da anotacao em classe")
    void qualifierBeatsAttributeOnClass() throws Exception {
        assertEquals("wanted", qualifierOf(QualifierBeatsAttribute.class));
    }

    @Test
    @DisplayName("4. atributo explicito vence @Primary em classe")
    void attributeBeatsPrimaryOnClass() throws Exception {
        assertEquals("fromComponent", qualifierOf(AttributeBeatsPrimary.class));
    }

    @Test
    @DisplayName("5. qualifier 'default' explicito conta como nao especificado em classe")
    void explicitDefaultIsUnspecifiedOnClass() throws Exception {
        assertTrue(qualifierOf(ExplicitDefaultIsUnspecified.class).startsWith("$primary$:"));
    }

    @Test
    @DisplayName("6. @Primary continua valendo quando nada foi especificado")
    void primaryStillWorksOnClass() throws Exception {
        assertTrue(qualifierOf(PrimaryOnly.class).startsWith("$primary$:"));
    }

    @Test
    @DisplayName("7. sem nada, classe continua em default")
    void classFallsBackToDefault() throws Exception {
        assertEquals("default", qualifierOf(Nothing.class));
    }

    @Test
    @DisplayName("8. @Inject.qualifier e lido em parametro")
    void injectQualifierOnParameter() throws Exception {
        assertEquals("wanted", qualifierOf("injectQualifier"));
    }

    @Test
    @DisplayName("9. @Qualifier vence @Inject.qualifier em parametro")
    void qualifierBeatsInjectOnParameter() throws Exception {
        assertEquals("wanted", qualifierOf("qualifierBeatsInject"));
    }

    @Test
    @DisplayName("10. parametro sem anotacao continua em default")
    void parameterFallsBackToDefault() throws Exception {
        assertEquals("default", qualifierOf("plain"));
    }

    private String qualifierOf(Class<?> clazz) throws Exception {
        return (String) resolveClass.invoke(container, clazz);
    }

    private String qualifierOf(String methodName) throws Exception {
        Parameter parameter = ParameterCases.class
                .getDeclaredMethod(methodName, String.class)
                .getParameters()[0];
        return (String) resolveParameter.invoke(container, parameter);
    }
}
