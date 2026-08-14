package dtm.di.external;

import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.ExternalFixtures;
import dtm.di.testsupport.ExternalModule;
import dtm.di.testsupport.MainCounter;
import dtm.di.testsupport.Probe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalLoadTest {

    private static ExternalModule module;

    private DependencyContainerStorage container;

    @BeforeAll
    static void compileModule() {
        module = ExternalModule.compile("load", ExternalFixtures.sources());
    }

    @AfterAll
    static void closeModule() {
        module.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        Probe.reset();
        container = ContainerFixture.newLoadedContainer("test");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. singleton externo é criado uma vez e reutilizado")
    void externalSingleton() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        container.loadExternal(List.of(baseService));

        Object first = container.getDependency(baseService);
        Object second = container.getDependency(baseService);

        assertNotNull(first);
        assertSame(first, second);
        assertEquals("base", ContainerFixture.invoke(first, "greet"));
        assertEquals(1, Probe.count("BaseService.post"));
    }

    @Test
    @DisplayName("2. prototype externo cria uma instância por resolução")
    void externalPrototype() throws Exception {
        Class<?> prototype = module.load(ExternalFixtures.PROTOTYPE_SERVICE);
        container.loadExternal(List.of(prototype));

        Object first = container.getDependency(prototype);
        Object second = container.getDependency(prototype);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    @DisplayName("3. constructor injection entre componentes externos")
    void constructorInjectionBetweenExternals() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.CTOR_CONSUMER);
        container.loadExternal(classes);

        Object consumer = container.getDependency(classes.get(1));

        assertEquals("ctor:base", ContainerFixture.invoke(consumer, "describe"));
    }

    @Test
    @DisplayName("4. field injection entre componentes externos")
    void fieldInjectionBetweenExternals() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.FIELD_CONSUMER);
        container.loadExternal(classes);

        Object consumer = container.getDependency(classes.get(1));

        assertEquals("field:base", ContainerFixture.invoke(consumer, "describe"));
    }

    @Test
    @DisplayName("5. dependência externa usando componente do container principal")
    void externalUsingMainComponent() throws Exception {
        Class<?> mainAware = module.load(ExternalFixtures.MAIN_AWARE);
        container.loadExternal(List.of(mainAware));

        Object instance = container.getDependency(mainAware);

        assertEquals("main-counter", ContainerFixture.invoke(instance, "describe"));
        assertEquals(1, ContainerFixture.invoke(instance, "useMain"));
        assertEquals(1, container.getDependency(MainCounter.class).value());
    }

    @Test
    @DisplayName("6. injeção por interface externa")
    void interfaceInjection() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.INTERFACE_CONSUMER);
        container.loadExternal(classes);

        Object consumer = container.getDependency(classes.get(1));
        Object byInterface = container.getDependency(module.load(ExternalFixtures.GREETER));

        assertEquals("interface:base", ContainerFixture.invoke(consumer, "describe"));
        assertNotNull(byInterface);
    }

    @Test
    @DisplayName("7. qualifiers diferentes para a mesma interface coexistem")
    void qualifiersOnSameInterface() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.ALPHA_SPEAKER, ExternalFixtures.BETA_SPEAKER);
        container.loadExternal(classes);

        Class<?> speaker = module.load(ExternalFixtures.SPEAKER);
        Object alpha = container.getDependency(speaker, "alpha");
        Object beta = container.getDependency(speaker, "beta");

        assertEquals("alpha", ContainerFixture.invoke(alpha, "speak"));
        assertEquals("beta", ContainerFixture.invoke(beta, "speak"));
        assertEquals(2, container.getDependencyList(speaker).size());
    }

    @Test
    @DisplayName("8. @Primary vence a resolução default")
    void primaryWins() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.PRIMARY_REPORTER, ExternalFixtures.FALLBACK_REPORTER);
        container.loadExternal(classes);

        Class<?> reporter = module.load(ExternalFixtures.REPORTER);

        assertEquals("primary", ContainerFixture.invoke(container.getDependency(reporter), "report"));
        assertEquals("fallback", ContainerFixture.invoke(container.getDependency(reporter, "fallback"), "report"));
        assertTrue(ContainerFixture.primaryIndexOf(container).containsKey(reporter));
    }

    @Test
    @DisplayName("mais de um @Primary para o mesmo tipo falha e reverte o lote")
    void duplicatePrimaryFailsAndRollsBackBatch() throws Exception {
        List<Class<?>> classes = module.load(
                ExternalFixtures.PRIMARY_REPORTER,
                ExternalFixtures.SECOND_PRIMARY_REPORTER
        );
        Class<?> reporter = module.load(ExternalFixtures.REPORTER);

        InvalidClassRegistrationException error = assertThrows(
                InvalidClassRegistrationException.class,
                () -> container.loadExternal(classes)
        );

        assertTrue(error.getMessage().contains("@Primary"));
        assertFalse(ContainerFixture.primaryIndexOf(container).containsKey(reporter));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
        for (Class<?> primary : classes) {
            assertFalse(container.hasDependecy(primary));
        }
    }

    @Test
    @DisplayName("9. componente externo com @Profile ativo é registrado")
    void activeProfileIsRegistered() throws Exception {
        Class<?> active = module.load(ExternalFixtures.ACTIVE_PROFILE_SERVICE);
        container.loadExternal(List.of(active));

        assertEquals("active", ContainerFixture.invoke(container.getDependency(active), "value"));
    }

    @Test
    @DisplayName("10. componente externo com @Profile inativo é ignorado")
    void inactiveProfileIsIgnored() throws Exception {
        Class<?> inactive = module.load(ExternalFixtures.INACTIVE_PROFILE_SERVICE);
        container.loadExternal(List.of(inactive));

        assertFalse(container.hasDependecy(inactive));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
    }

    @Test
    @DisplayName("11 e 12. classe @Configuration externa e bean produzido por método")
    void externalConfigurationProducesBeans() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.EXTERNAL_CONFIGURATION);
        container.loadExternal(classes);

        Object configBean = container.getDependency(module.load(ExternalFixtures.CONFIG_BEAN));

        assertNotNull(configBean);
        assertEquals("from-config", ContainerFixture.invoke(configBean, "value"));
        assertEquals(1, Probe.count("ExternalConfiguration.configBean"));
    }

    @Test
    @DisplayName("@Profile em método produtor filtra o bean antes da execução")
    void profileOnProducerMethodFiltersBean() throws Exception {
        Class<?> configuration = module.load(ExternalFixtures.PROFILED_PRODUCER_CONFIGURATION);
        container.loadExternal(List.of(configuration));

        Class<?> activeType = module.load(ExternalFixtures.CONFIG_BEAN);
        Class<?> inactiveType = module.load(ExternalFixtures.ORPHAN_BEAN);

        assertEquals("profile-test", ContainerFixture.invoke(container.getDependency(activeType), "value"));
        assertFalse(container.hasDependecy(inactiveType));
        assertEquals(1, Probe.count("ProfiledProducer.active"));
        assertEquals(0, Probe.count("ProfiledProducer.inactive"));
    }

    @Test
    @DisplayName("produtor @Async registra wrapper sem bloquear produtores posteriores")
    void asyncProducerRegistersWrapperWithoutBlocking() throws Exception {
        Class<?> configuration = module.load(ExternalFixtures.ASYNC_PRODUCER_CONFIGURATION);
        container.loadExternal(List.of(configuration));

        Class<?> asyncType = module.load(ExternalFixtures.CONFIG_BEAN);
        Class<?> dependentType = module.load(ExternalFixtures.COMPONENT_AWARE_BEAN);
        Object dependent = container.getDependency(dependentType);
        Object asyncBean = container.getDependencyAsync(asyncType, "default", true).getAsync().await();

        assertEquals("async-wrapper", ContainerFixture.invoke(dependent, "value"));
        assertEquals("async", ContainerFixture.invoke(asyncBean, "value"));
        assertTrue(Probe.events().stream().anyMatch(event -> event.startsWith("AsyncProducer.thread:MainExecutor-Worker-")));
        assertEquals(1, Probe.count("AsyncProducer.dependent"));
    }

    @Test
    @DisplayName("falha de produtor @Async fica no resultado e não reverte carga concluída")
    void asyncProducerFailureCompletesResultExceptionally() throws Exception {
        Class<?> configuration = module.load(ExternalFixtures.FAILING_ASYNC_PRODUCER_CONFIGURATION);
        Class<?> orphanType = module.load(ExternalFixtures.ORPHAN_BEAN);
        Class<?> asyncType = module.load(ExternalFixtures.CONFIG_BEAN);

        container.loadExternal(List.of(configuration));

        assertThrows(
                CompletionException.class,
                () -> container.getDependencyAsync(asyncType, "default", true).getAsync().await()
        );
        assertTrue(container.hasDependecy(orphanType));
        assertFalse(ContainerFixture.externalRegistrationsOf(container).isEmpty());
    }

    @Test
    @DisplayName("13. bean de configuração dependente de componente externo")
    void configurationBeanDependingOnExternalComponent() throws Exception {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.EXTERNAL_CONFIGURATION);
        container.loadExternal(classes);

        Object bean = container.getDependency(module.load(ExternalFixtures.COMPONENT_AWARE_BEAN));

        assertNotNull(bean);
        assertEquals("config:base", ContainerFixture.invoke(bean, "value"));
    }

    @Test
    @DisplayName("14. @PostCreation executa no carregamento externo")
    void postCreationIsInvoked() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);
        container.loadExternal(List.of(baseService));

        assertTrue(Probe.contains("BaseService.post"));
        assertEquals(1, Probe.count("BaseService.post"));
    }

    @Test
    @DisplayName("@Value é resolvido em componentes externos")
    void valueAnnotationIsResolved() throws Exception {
        Class<?> valueService = module.load(ExternalFixtures.VALUE_SERVICE);
        container.loadExternal(List.of(valueService));

        assertEquals("fallback-value", ContainerFixture.invoke(container.getDependency(valueService), "setting"));
    }

    @Test
    @DisplayName("17. ciclo de dependência externo é detectado antes de criar componentes")
    void dependencyCycleIsRejected() {
        List<Class<?>> classes = module.load(ExternalFixtures.CYCLE_A, ExternalFixtures.CYCLE_B);

        assertThrows(InvalidClassRegistrationException.class, () -> container.loadExternal(classes));

        assertFalse(container.hasDependecy(classes.get(0)));
        assertFalse(container.hasDependecy(classes.get(1)));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
        assertTrue(container.isLoaded());
    }

    @Test
    @DisplayName("18. falha no lote desfaz tudo que já havia sido registrado")
    void failureRollsBackTheWholeBatch() {
        List<Class<?>> classes = module.load(ExternalFixtures.BASE_SERVICE, ExternalFixtures.BROKEN_SERVICE);

        assertThrows(InvalidClassRegistrationException.class, () -> container.loadExternal(classes));

        assertFalse(container.hasDependecy(classes.get(0)));
        assertFalse(container.hasDependecy(classes.get(1)));
        assertFalse(container.hasDependecy(module.load(ExternalFixtures.GREETER)));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
        assertTrue(Probe.contains("BaseService.post"));
        assertEquals(1, Probe.count("BaseService.destroy"));
        assertTrue(container.isLoaded());
        assertNotNull(container.getDependency(MainCounter.class));
    }

    @Test
    @DisplayName("34. falha em método produtor de configuração desfaz os beans do lote")
    void configurationProducerFailureRollsBack() {
        List<Class<?>> classes = List.of(module.load(ExternalFixtures.FAILING_CONFIGURATION));

        assertThrows(InvalidClassRegistrationException.class, () -> container.loadExternal(classes));

        assertFalse(container.hasDependecy(module.load(ExternalFixtures.ORPHAN_BEAN)));
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
        assertTrue(container.isLoaded());
    }

    @Test
    @DisplayName("28. loadExternal é idempotente")
    void loadExternalIsIdempotent() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);

        container.loadExternal(List.of(baseService));
        Object first = container.getDependency(baseService);

        container.loadExternal(List.of(baseService, baseService));
        Object second = container.getDependency(baseService);

        assertSame(first, second);
        assertEquals(1, Probe.count("BaseService.post"));
        assertEquals(1, ContainerFixture.externalRegistrationsOf(container).size());
    }

    @Test
    @DisplayName("29. coleção vazia é no-op")
    void emptyCollectionIsNoOp() throws Exception {
        container.loadExternal(List.of());
        container.unload(List.of());

        assertTrue(container.isLoaded());
        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
    }

    @Test
    @DisplayName("30. elementos inválidos são rejeitados ou ignorados")
    void invalidElements() throws Exception {
        assertThrows(NullPointerException.class, () -> container.loadExternal(null));
        assertThrows(NullPointerException.class, () -> container.unload((List<Class<?>>) null));

        List<Class<?>> withNull = new ArrayList<>();
        withNull.add(module.load(ExternalFixtures.BASE_SERVICE));
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> container.loadExternal(withNull));
        assertThrows(IllegalArgumentException.class, () -> container.unload(withNull));
        assertFalse(container.hasDependecy(module.load(ExternalFixtures.BASE_SERVICE)));

        List<Class<?>> ignored = module.load(
                ExternalFixtures.PLAIN_CLASS,
                ExternalFixtures.GREETER,
                ExternalFixtures.ABSTRACT_COMPONENT,
                ExternalFixtures.COMPONENT_ENUM,
                ExternalFixtures.COMPONENT_RECORD,
                ExternalFixtures.COMPONENT_ANNOTATION
        );

        container.loadExternal(ignored);

        assertTrue(ContainerFixture.externalRegistrationsOf(container).isEmpty());
        for (Class<?> clazz : ignored) {
            assertFalse(container.hasDependecy(clazz), () -> "nao deveria registrar " + clazz.getName());
        }
    }

    @Test
    @DisplayName("32. isLoaded continua verdadeiro após carga e descarga externas")
    void containerStaysLoaded() throws Exception {
        Class<?> baseService = module.load(ExternalFixtures.BASE_SERVICE);

        container.loadExternal(List.of(baseService));
        assertTrue(container.isLoaded());

        container.unload(List.of(baseService));
        assertTrue(container.isLoaded());
        assertNotNull(container.getDependency(MainCounter.class));
    }

    @Test
    @DisplayName("loadExternal exige o container carregado")
    void loadExternalRequiresLoadedContainer() {
        container.unload();

        List<Class<?>> classes = List.of(module.load(ExternalFixtures.BASE_SERVICE));

        assertThrows(dtm.di.exceptions.UnloadError.class, () -> container.loadExternal(classes));
        assertThrows(dtm.di.exceptions.UnloadError.class, () -> container.unload(classes));
    }

    @Test
    @DisplayName("AsyncComponent externo é registrado")
    void asyncComponentIsRegistered() throws Exception {
        Class<?> asyncService = module.load(ExternalFixtures.ASYNC_SERVICE);
        container.loadExternal(List.of(asyncService));

        assertTrue(container.hasDependecy(dtm.di.prototypes.async.AsyncComponent.class, "default"));
        assertTrue(waitForProbe("AsyncService.created"));
    }

    static boolean waitForProbe(String event) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Probe.contains(event)) {
                return true;
            }
            Thread.sleep(20);
        }
        return Probe.contains(event);
    }

    static List<String> destructionOrder(List<String> events, String... expected) {
        List<String> filtered = new ArrayList<>();
        List<String> wanted = Arrays.asList(expected);
        for (String event : events) {
            if (wanted.contains(event)) {
                filtered.add(event);
            }
        }
        return filtered;
    }
}
