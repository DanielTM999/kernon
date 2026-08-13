package dtm.di.external;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.MainCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalSmokeTest {

    private DependencyContainerStorage container;

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    void mainContainerLoadsTestComponents() throws Exception {
        long start = System.currentTimeMillis();
        container = ContainerFixture.newLoadedContainer("test");
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("load() levou " + elapsed + "ms com " + container.getLoadedSystemClasses().size() + " classes");

        assertTrue(container.isLoaded());
        assertNotNull(container.getDependency(MainCounter.class));
    }

    @Test
    void externalClassesAreInvisibleToTheKernonClassLoader() throws Exception {
        try (ExternalModule module = ExternalModule.compile("smoke", Map.of(
                "ext.SmokeService",
                """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                public class SmokeService {
                    public String value() {
                        return "smoke";
                    }
                }
                """
        ))) {
            Class<?> serviceClass = module.load("ext.SmokeService");

            assertEquals(module.classLoader(), serviceClass.getClassLoader());
            assertTrue(getClass().getClassLoader() != serviceClass.getClassLoader());

            container = ContainerFixture.newLoadedContainer("test");
            container.loadExternal(List.of(serviceClass));

            Object instance = container.getDependency(serviceClass);
            assertNotNull(instance);
            assertEquals("smoke", ContainerFixture.invoke(instance, "value"));

            container.unload(List.of(serviceClass));
            assertTrue(container.isLoaded());
        }
    }
}
