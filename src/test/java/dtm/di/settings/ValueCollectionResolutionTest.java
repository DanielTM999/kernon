package dtm.di.settings;

import dtm.di.annotations.settings.Value;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueCollectionResolutionTest {

    private static AppSettings settings;
    private static Method resolveValue;
    private static DependencyContainerStorage container;

    @BeforeAll
    static void setUp() throws Exception {
        container = ContainerFixture.newContainer("value-collections");
        settings = new JsonAppSettings(JsonAppSettings.DEFAULT_RESOURCE_NAME, "value-collections");
        resolveValue = DependencyContainerStorage.class.getDeclaredMethod(
                "resolveValue", Value.class, Class.class, java.lang.reflect.Type.class, AppSettings.class);
        resolveValue.setAccessible(true);
    }

    @AfterAll
    static void tearDown() {
        ContainerFixture.dispose(container);
    }

    private Object resolve(String fieldName) throws Exception {
        Field field = Holder.class.getDeclaredField(fieldName);
        return resolveValue.invoke(container, field.getAnnotation(Value.class),
                field.getType(), field.getGenericType(), settings);
    }

    @Test
    void resolvesInterfaceListWithGenericElements() throws Exception {
        @SuppressWarnings("unchecked")
        List<ServerConfig> servers = (List<ServerConfig>) resolve("servers");

        assertInstanceOf(ArrayList.class, servers);
        assertEquals(2, servers.size());
        assertEquals("alpha", servers.get(0).host);
        assertEquals(9090, servers.get(1).port);
    }

    @Test
    void resolvesConcreteCollectionTypes() throws Exception {
        assertInstanceOf(LinkedList.class, resolve("serversLinked"));
        assertInstanceOf(ArrayList.class, resolve("serversConcrete"));

        Object sortedTags = resolve("tagsSorted");
        assertInstanceOf(TreeSet.class, sortedTags);
        assertEquals(List.of("a", "b"), List.copyOf((TreeSet<?>) sortedTags));
    }

    @Test
    void resolvesInterfaceSetAndMapWithDefaultImplementations() throws Exception {
        assertInstanceOf(Set.class, resolve("tags"));
        assertEquals(Set.of("a", "b"), resolve("tags"));

        Object limits = resolve("limits");
        assertInstanceOf(LinkedHashMap.class, limits);
        assertEquals(Map.of("read", 10, "write", 20), limits);
    }

    @Test
    void resolvesArrays() throws Exception {
        String[] tags = (String[]) resolve("tagsArray");
        assertEquals(3, tags.length);
        assertEquals("b", tags[0]);

        assertEquals(0, ((String[]) resolve("missingArray")).length);
    }

    @Test
    void missingContainerKeysBecomeEmptyContainers() throws Exception {
        assertEquals(List.of(), resolve("missingList"));
        assertInstanceOf(ArrayList.class, resolve("missingList"));
        assertEquals(Map.of(), resolve("missingMap"));
        assertInstanceOf(LinkedHashMap.class, resolve("missingMap"));
        assertInstanceOf(TreeSet.class, resolve("missingSortedSet"));
        assertInstanceOf(LinkedHashSet.class, resolve("missingSet"));
        assertInstanceOf(LinkedList.class, resolve("missingLinked"));
    }

    @Test
    void resolvesOptionalValues() throws Exception {
        Optional<?> single = (Optional<?>) resolve("single");
        assertTrue(single.isPresent());
        assertEquals("gamma", ((ServerConfig) single.get()).host);

        assertTrue(((Optional<?>) resolve("missingSingle")).isEmpty());
        assertTrue(((Optional<?>) resolve("missingNumber")).isEmpty());
        assertEquals(Optional.of(7), resolve("numberWithDefault"));

        Optional<?> optionalTags = (Optional<?>) resolve("optionalTags");
        assertNotNull(optionalTags);
        assertTrue(optionalTags.isPresent());
        assertEquals(List.of("b", "a", "b"), optionalTags.get());
    }

    @SuppressWarnings("unused")
    static class Holder {
        @Value(key = "app.servers")
        List<ServerConfig> servers;

        @Value(key = "app.servers")
        ArrayList<ServerConfig> serversConcrete;

        @Value(key = "app.servers")
        LinkedList<ServerConfig> serversLinked;

        @Value(key = "app.tags")
        Set<String> tags;

        @Value(key = "app.tags")
        TreeSet<String> tagsSorted;

        @Value(key = "app.tags")
        String[] tagsArray;

        @Value(key = "app.limits")
        Map<String, Integer> limits;

        @Value(key = "app.absent")
        List<ServerConfig> missingList;

        @Value(key = "app.absent")
        Map<String, Integer> missingMap;

        @Value(key = "app.absent")
        TreeSet<String> missingSortedSet;

        @Value(key = "app.absent")
        Set<String> missingSet;

        @Value(key = "app.absent")
        LinkedList<String> missingLinked;

        @Value(key = "app.absent")
        String[] missingArray;

        @Value(key = "app.single")
        Optional<ServerConfig> single;

        @Value(key = "app.absent")
        Optional<ServerConfig> missingSingle;

        @Value(key = "app.absent")
        Optional<Integer> missingNumber;

        @Value(key = "app.absent", defaultValue = "7")
        Optional<Integer> numberWithDefault;

        @Value(key = "app.tags")
        Optional<List<String>> optionalTags;
    }

    public static class ServerConfig {
        public String host;
        public int port;
    }
}
