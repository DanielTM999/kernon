package dtm.di.core;

import dtm.di.exceptions.DependencyInjectionException;
import dtm.di.prototypes.CompositeDependency;
import dtm.di.prototypes.LazyDependency;
import dtm.di.prototypes.async.AsyncComponent;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.FooPayload;
import dtm.di.testsupport.GenericProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncNestingValidationTest {

    static class Holder {
        AsyncComponent<GenericProcessor<FooPayload>> genericTarget;
        AsyncComponent<FooPayload> simpleTarget;
        AsyncComponent<AsyncComponent<FooPayload>> nestedAsync;
        AsyncComponent<LazyDependency<FooPayload>> nestedLazy;
        AsyncComponent<List<FooPayload>> nestedList;
        AsyncComponent<CompositeDependency<FooPayload>> nestedComposite;
    }

    private DependencyContainerStorage container;
    private Method resolveField;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("generics");
        resolveField = DependencyContainerStorage.class
                .getDeclaredMethod("getDependencyObjectByField", Field.class, Object.class);
        resolveField.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. argumento generico comum nao e rejeitado como aninhamento")
    void genericArgumentIsAllowed() {
        assertDoesNotThrow(() -> resolve("genericTarget"));
    }

    @Test
    @DisplayName("2. argumento simples continua permitido")
    void simpleArgumentIsAllowed() {
        assertDoesNotThrow(() -> resolve("simpleTarget"));
    }

    @Test
    @DisplayName("3. AsyncComponent dentro de AsyncComponent e rejeitado")
    void nestedAsyncIsRejected() {
        assertNestingRejected("nestedAsync", AsyncComponent.class);
    }

    @Test
    @DisplayName("4. LazyDependency dentro de AsyncComponent e rejeitado")
    void nestedLazyIsRejected() {
        assertNestingRejected("nestedLazy", LazyDependency.class);
    }

    @Test
    @DisplayName("5. List dentro de AsyncComponent e rejeitado")
    void nestedListIsRejected() {
        assertNestingRejected("nestedList", List.class);
    }

    @Test
    @DisplayName("6. CompositeDependency dentro de AsyncComponent e rejeitado")
    void nestedCompositeIsRejected() {
        assertNestingRejected("nestedComposite", CompositeDependency.class);
    }

    private void assertNestingRejected(String fieldName, Class<?> nestedWrapper) {
        Throwable error = assertThrows(Throwable.class, () -> resolve(fieldName));
        DependencyInjectionException cause = assertInstanceOf(DependencyInjectionException.class, error);

        assertTrue(cause.getMessage().contains(nestedWrapper.getName()));
        assertTrue(cause.getMessage().contains(AsyncComponent.class.getSimpleName()));
    }

    private Object resolve(String fieldName) throws Throwable {
        Field field = Holder.class.getDeclaredField(fieldName);
        try {
            return resolveField.invoke(container, field, "AsyncNestingValidationTest");
        } catch (InvocationTargetException e) {
            throw (e.getCause() != null) ? e.getCause() : e;
        }
    }
}
