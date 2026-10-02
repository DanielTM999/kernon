package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.BoletoPayment;
import dtm.di.testsupport.CardPayment;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.PaymentMapConsumer;
import dtm.di.testsupport.PaymentMethod;
import dtm.di.testsupport.PixPayment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanMapInjectionTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("beanmap");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. Map em campo usa qualifier como chave e FQCN em caso de colisao")
    void fieldMapUsesQualifierAndFallsBackToClassNameOnCollision() {
        PaymentMapConsumer consumer = container.getDependency(PaymentMapConsumer.class);

        assertNotNull(consumer);
        assertExpectedPayments(consumer.byField());
    }

    @Test
    @DisplayName("2. Map em construtor recebe os mesmos beans")
    void constructorMapIsInjected() {
        PaymentMapConsumer consumer = container.getDependency(PaymentMapConsumer.class);

        assertExpectedPayments(consumer.byConstructor());
    }

    @Test
    @DisplayName("3. LazyDependency de Map resolve o map de beans")
    void lazyMapResolves() {
        PaymentMapConsumer consumer = container.getDependency(PaymentMapConsumer.class);

        assertExpectedPayments(consumer.lazyMap().get());
    }

    @Test
    @DisplayName("4. Map sem candidatos e injetado vazio")
    void mapWithoutCandidatesIsEmpty() {
        PaymentMapConsumer consumer = container.getDependency(PaymentMapConsumer.class);

        assertNotNull(consumer.emptyMap());
        assertTrue(consumer.emptyMap().isEmpty());
    }

    private void assertExpectedPayments(Map<String, PaymentMethod> payments) {
        assertNotNull(payments);
        assertEquals(
                Set.of("pix", BoletoPayment.class.getName(), CardPayment.class.getName()),
                payments.keySet()
        );
        assertInstanceOf(PixPayment.class, payments.get("pix"));
        assertInstanceOf(BoletoPayment.class, payments.get(BoletoPayment.class.getName()));
        assertInstanceOf(CardPayment.class, payments.get(CardPayment.class.getName()));
    }
}
