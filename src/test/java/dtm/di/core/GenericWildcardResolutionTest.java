package dtm.di.core;

import dtm.di.exceptions.AmbiguousDependencyException;
import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import dtm.di.testsupport.GenericProcessor;
import dtm.di.testsupport.GenericWildcardConsumer;
import dtm.di.testsupport.IntegerProcessor;
import dtm.di.testsupport.PayloadProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericWildcardResolutionTest {

    private DependencyContainerStorage container;

    @BeforeEach
    void setUp() throws Exception {
        container = ContainerFixture.newLoadedContainer("generics-wildcard");
    }

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. wildcard super resolve o unico candidato compativel")
    void lowerBoundedWildcardResolvesSingleCandidate() {
        GenericWildcardConsumer consumer = container.getDependency(GenericWildcardConsumer.class);

        assertNotNull(consumer);
        assertInstanceOf(IntegerProcessor.class, consumer.lowerBounded());
    }

    @Test
    @DisplayName("2. match exato continua valendo quando ha varios candidatos crus")
    void exactMatchStillResolves() {
        GenericWildcardConsumer consumer = container.getDependency(GenericWildcardConsumer.class);

        assertInstanceOf(PayloadProcessor.class, consumer.exact());
    }

    @Test
    @DisplayName("3. FAIL_FAST lanca quando o tipo cru tem varios candidatos")
    void rawLookupFailsFastWhenContested() {
        AmbiguousDependencyException error = assertThrows(
                AmbiguousDependencyException.class,
                () -> container.getDependency(GenericProcessor.class)
        );

        assertTrue(error.getCandidates().size() > 1);
        assertTrue(error.getMessage().contains(IntegerProcessor.class.getName()));
    }

    @Test
    @DisplayName("4. LOG registra o erro e devolve null em vez de lancar")
    void logPolicyReturnsNull() {
        container.setAmbiguityPolicy(AmbiguityPolicy.LOG);

        assertNull(container.getDependency(GenericProcessor.class));
    }

    @Test
    @DisplayName("5. SILENT mantem o comportamento arbitrario anterior")
    void silentPolicyReturnsSomeCandidate() {
        container.setAmbiguityPolicy(AmbiguityPolicy.SILENT);

        assertNotNull(container.getDependency(GenericProcessor.class));
    }
}
