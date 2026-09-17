package dtm.di.core;

import dtm.di.storage.containers.DependencyContainerStorage;
import dtm.di.testsupport.ContainerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbiguityPolicySettingsTest {

    private DependencyContainerStorage container;

    @AfterEach
    void tearDown() {
        ContainerFixture.dispose(container);
    }

    @Test
    @DisplayName("1. sem settings e sem setter a politica padrao e FAIL_FAST")
    void defaultPolicyIsFailFast() throws Exception {
        container = ContainerFixture.newContainer("configuration-settings");

        container.load();

        assertEquals(AmbiguityPolicy.FAIL_FAST, ContainerFixture.ambiguityPolicyOf(container));
    }

    @Test
    @DisplayName("2. a politica declarada nos settings e aplicada")
    void appliesPolicyFromSettings() throws Exception {
        container = ContainerFixture.newContainer("ambiguity-log");

        container.load();

        assertEquals(AmbiguityPolicy.LOG, ContainerFixture.ambiguityPolicyOf(container));
    }

    @Test
    @DisplayName("3. a configuracao programatica tem precedencia sobre os settings")
    void programmaticPolicyTakesPrecedence() throws Exception {
        container = ContainerFixture.newContainer("ambiguity-log");
        container.setAmbiguityPolicy(AmbiguityPolicy.SILENT);

        container.load();

        assertEquals(AmbiguityPolicy.SILENT, ContainerFixture.ambiguityPolicyOf(container));
    }

    @Test
    @DisplayName("4. setter com null seleciona o padrao FAIL_FAST e mantem a precedencia")
    void programmaticNullSelectsDefault() throws Exception {
        container = ContainerFixture.newContainer("ambiguity-log");
        container.setAmbiguityPolicy(null);

        container.load();

        assertEquals(AmbiguityPolicy.FAIL_FAST, ContainerFixture.ambiguityPolicyOf(container));
    }

    @Test
    @DisplayName("5. politica invalida nos settings volta para FAIL_FAST")
    void invalidDeclarativePolicyFallsBackToFailFast() throws Exception {
        container = ContainerFixture.newContainer("ambiguity-invalid");

        container.load();

        assertEquals(AmbiguityPolicy.FAIL_FAST, ContainerFixture.ambiguityPolicyOf(container));
    }

    @Test
    @DisplayName("6. a resolucao generica vem habilitada por padrao")
    void genericResolutionEnabledByDefault() throws Exception {
        container = ContainerFixture.newContainer("configuration-settings");

        container.load();

        assertTrue(ContainerFixture.genericResolutionEnabledOf(container));
    }

    @Test
    @DisplayName("7. a resolucao generica pode ser desligada pelos settings")
    void genericResolutionCanBeDisabledBySettings() throws Exception {
        container = ContainerFixture.newContainer("generic-resolution-off");

        container.load();

        assertFalse(ContainerFixture.genericResolutionEnabledOf(container));
    }

    @Test
    @DisplayName("8. o setter de resolucao generica tem precedencia sobre os settings")
    void programmaticGenericResolutionTakesPrecedence() throws Exception {
        container = ContainerFixture.newContainer("generic-resolution-off");
        container.setGenericResolutionEnabled(true);

        container.load();

        assertTrue(ContainerFixture.genericResolutionEnabledOf(container));
    }
}
