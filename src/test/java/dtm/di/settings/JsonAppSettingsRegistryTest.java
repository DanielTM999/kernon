package dtm.di.settings;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAppSettingsRegistryTest {

    @TempDir
    Path tempDir;

    private final AtomicInteger directorySequence = new AtomicInteger();

    @Test
    void mergesRecursivelyWithKeepAndOverride() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                """
                {
                  "settingsRegistry": {"allowedModes": ["KEEP", "OVERRIDE"]},
                  "tree": {"current": 1, "nullable": null},
                  "array": [1]
                }
                """
        );

        settings.register("""
                {
                  "tree": {"current": 2, "added": 3, "nullable": "new"},
                  "array": [2]
                }
                """);

        assertEquals(1, settings.getInt("tree.current", -1));
        assertEquals(3, settings.getInt("tree.added", -1));
        assertTrue(settings.has("tree.nullable"));
        assertEquals("[1]", settings.getString("array", ""));

        settings.register("""
                {
                  "tree": {"current": 4, "nullable": "overridden"},
                  "array": [4]
                }
                """, SettingsRegistrationMode.OVERRIDE);

        assertEquals(4, settings.getInt("tree.current", -1));
        assertEquals("overridden", settings.getString("tree.nullable", ""));
        assertEquals("[4]", settings.getString("array", ""));
    }

    @Test
    void defaultsToKeepAndBlocksOverride() throws Exception {
        JsonAppSettings settings = settingsWithPolicy("{\"value\": 1}");

        settings.register("{\"value\": 2, \"newValue\": 3}");

        assertEquals(1, settings.getInt("value", -1));
        assertEquals(3, settings.getInt("newValue", -1));
        assertThrows(
                SettingsRegistrationBlockedException.class,
                () -> settings.register("{\"value\": 4}", SettingsRegistrationMode.OVERRIDE)
        );
    }

    @Test
    void disabledRegistryBlocksEverySource() throws Exception {
        JsonAppSettings settings = settingsWithPolicy("""
                {"settingsRegistry": {"enabled": false, "allowedModes": ["KEEP", "OVERRIDE"]}}
                """);

        assertThrows(SettingsRegistrationBlockedException.class, () -> settings.register("{\"a\": 1}"));
        assertThrows(
                SettingsRegistrationBlockedException.class,
                () -> settings.register(tempDir.resolve("never-read.json"), SettingsRegistrationMode.OVERRIDE)
        );
    }

    @Test
    void canAllowOnlyOverrideMode() throws Exception {
        JsonAppSettings settings = settingsWithPolicy("""
                {
                  "settingsRegistry": {"allowedModes": ["OVERRIDE"]},
                  "value": 1
                }
                """);

        assertThrows(SettingsRegistrationBlockedException.class, () -> settings.register("{\"value\": 2}"));
        settings.register("{\"value\": 3}", SettingsRegistrationMode.OVERRIDE);

        assertEquals(3, settings.getInt("value", -1));
    }

    @Test
    void emptyAllowedModesFallsBackToKeepWithoutUnknownModeWarning() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(JsonAppSettings.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JsonAppSettings settings = settingsWithPolicy(
                    "{\"settingsRegistry\": {\"allowedModes\": []}}"
            );

            settings.register("{\"accepted\": true}");
            assertTrue(settings.getBoolean("accepted", false));
            assertFalse(appender.list.stream().anyMatch(event ->
                    event.getLevel() == Level.WARN && event.getFormattedMessage().contains("Modo desconhecido")
            ));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void unknownAllowedModeWarnsAndFallsBackToKeep() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(JsonAppSettings.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JsonAppSettings settings = settingsWithPolicy("""
                    {"settingsRegistry": {"allowedModes": ["KEEP", "INVALID"]}}
                    """);

            assertThrows(
                    SettingsRegistrationBlockedException.class,
                    () -> settings.register("{\"a\": 1}", SettingsRegistrationMode.OVERRIDE)
            );
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getLevel() == Level.WARN && event.getFormattedMessage().contains("INVALID")
            ));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void policyOverrideCanFailAtomically() throws Exception {
        JsonAppSettings settings = settingsWithPolicy("""
                {
                  "settingsRegistry": {
                    "allowedModes": ["KEEP", "OVERRIDE"],
                    "failOnPolicyOverride": true
                  },
                  "stable": 1
                }
                """);

        assertThrows(SettingsRegistrationBlockedException.class, () -> settings.register("""
                {"settingsRegistry": {"enabled": false}, "stable": 2, "other": 3}
                """, SettingsRegistrationMode.OVERRIDE));

        assertEquals(1, settings.getInt("stable", -1));
        assertFalse(settings.has("other"));
    }

    @Test
    void policyOverrideCanBeSilentlyRemoved() throws Exception {
        JsonAppSettings settings = settingsWithPolicy("""
                {
                  "settingsRegistry": {
                    "allowedModes": ["KEEP", "OVERRIDE"],
                    "failOnPolicyOverride": false
                  },
                  "stable": 1
                }
                """);

        settings.register("""
                {"settingsRegistry": {"enabled": false}, "stable": 2, "other": 3}
                """, SettingsRegistrationMode.OVERRIDE);
        settings.register("{\"settingsRegistry\": {\"enabled\": false}}", SettingsRegistrationMode.OVERRIDE);

        assertEquals(2, settings.getInt("stable", -1));
        assertEquals(3, settings.getInt("other", -1));
        assertFalse(settings.getBoolean("settingsRegistry.failOnPolicyOverride", true));
        assertFalse(settings.has("settingsRegistry.enabled"));
    }

    @Test
    void registersAPathAsASingleDocumentAndKeepsStateOnFailure() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                "{\"settingsRegistry\": {\"allowedModes\": [\"KEEP\", \"OVERRIDE\"]}, \"stable\": 1}"
        );
        Path external = tempDir.resolve("external.json");
        Files.writeString(external, "{\"fromPath\": 2}");

        settings.register(external);
        assertEquals(2, settings.getInt("fromPath", -1));

        assertThrows(SettingsRegistrationException.class, () -> settings.register("[1, 2]"));
        assertThrows(SettingsRegistrationException.class, () -> settings.register("{broken"));
        assertEquals(1, settings.getInt("stable", -1));
        assertEquals(2, settings.getInt("fromPath", -1));
    }

    @Test
    void classLoaderLoadsBaseAndActiveProfiles() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                "{\"settingsRegistry\": {\"allowedModes\": [\"KEEP\", \"OVERRIDE\"]}}",
                "dev"
        );
        Path externalRoot = Files.createDirectories(tempDir.resolve("external-loader"));
        Files.writeString(externalRoot.resolve("settings.json"),
                "{\"externalLoader\": {\"base\": 1, \"shared\": \"base\"}}");
        Files.writeString(externalRoot.resolve("settings.dev.json"),
                "{\"externalLoader\": {\"profile\": 2, \"shared\": \"profile\"}}");

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{externalRoot.toUri().toURL()},
                null
        )) {
            settings.register(loader, SettingsRegistrationMode.OVERRIDE);
        }

        assertEquals(1, settings.getInt("externalLoader.base", -1));
        assertEquals(2, settings.getInt("externalLoader.profile", -1));
        assertEquals("profile", settings.getString("externalLoader.shared", ""));
    }

    @Test
    void classLoadsBaseAndActiveProfiles() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                "{\"settingsRegistry\": {\"allowedModes\": [\"KEEP\", \"OVERRIDE\"]}}",
                "dev"
        );

        settings.register(JsonAppSettingsRegistryTest.class, SettingsRegistrationMode.OVERRIDE);

        assertEquals("base", settings.getString("externalClass.base", ""));
        assertEquals("dev", settings.getString("externalClass.profile", ""));
        assertEquals("profile", settings.getString("externalClass.shared", ""));
    }

    @Test
    void missingClassLoaderBaseIsAnError() throws Exception {
        JsonAppSettings settings = settingsWithPolicy("{}");
        Path emptyRoot = Files.createDirectories(tempDir.resolve("empty-loader"));

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{emptyRoot.toUri().toURL()}, null)) {
            assertThrows(SettingsRegistrationException.class, () -> settings.register(loader));
        }
    }

    @Test
    void explicitRequiredTrueKeepsMissingClassLoaderBaseAsAnError() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                "{\"settingsRegistry\": {\"required\": true}}"
        );
        Path emptyRoot = Files.createDirectories(tempDir.resolve("empty-required-loader"));

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{emptyRoot.toUri().toURL()}, null)) {
            assertThrows(SettingsRegistrationException.class, () -> settings.register(loader));
        }
    }

    @Test
    void requiredFalseIgnoresClassLoaderWithoutSettingsResources() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                "{\"settingsRegistry\": {\"required\": false}, \"stable\": 1}",
                "dev"
        );
        Path emptyRoot = Files.createDirectories(tempDir.resolve("empty-optional-loader"));

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{emptyRoot.toUri().toURL()}, null)) {
            settings.register(loader);
        }

        assertEquals(1, settings.getInt("stable", -1));
    }

    @Test
    void requiredFalseLoadsAvailableProfileWithoutBaseSettings() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                "{\"settingsRegistry\": {\"required\": false}}",
                "dev"
        );
        Path externalRoot = Files.createDirectories(tempDir.resolve("profile-only-loader"));
        Files.writeString(externalRoot.resolve("settings.dev.json"), "{\"profileOnly\": true}");

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{externalRoot.toUri().toURL()},
                null
        )) {
            settings.register(loader);
        }

        assertTrue(settings.getBoolean("profileOnly", false));
    }

    @Test
    void supportsConcurrentReadsAndRegistrations() throws Exception {
        JsonAppSettings settings = settingsWithPolicy(
                "{\"settingsRegistry\": {\"allowedModes\": [\"KEEP\", \"OVERRIDE\"]}}"
        );
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int value = index;
                tasks.add(() -> {
                    settings.register("{\"values\": {\"v" + value + "\": " + value + "}}");
                    settings.getInt("values.v" + value, -1);
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) future.get();
        }

        for (int index = 0; index < 100; index++) {
            assertEquals(index, settings.getInt("values.v" + index, -1));
        }
    }

    @Test
    void defaultImplementationExposesBothContractsOnTheSameInstance() throws Exception {
        JsonAppSettings settings = settingsWithPolicy("{}");

        assertInstanceOf(AppSettings.class, settings);
        assertInstanceOf(AppSettingsRegistry.class, settings);
        assertTrue(((AppSettings) settings) == ((AppSettingsRegistry) settings));
    }

    private JsonAppSettings settingsWithPolicy(String mainSettings, String... profiles) throws IOException {
        Path resourceRoot = Files.createDirectories(
                tempDir.resolve("main-" + directorySequence.incrementAndGet())
        );
        Files.writeString(resourceRoot.resolve("main.json"), mainSettings);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{resourceRoot.toUri().toURL()},
                null
        )) {
            Thread.currentThread().setContextClassLoader(loader);
            return new JsonAppSettings("main.json", profiles);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
