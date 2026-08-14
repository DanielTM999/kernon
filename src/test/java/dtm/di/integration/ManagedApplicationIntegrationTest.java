package dtm.di.integration;

import dtm.di.integration.fixture.ManagedIntegrationApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedApplicationIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void managedBootRunsHooksSchedulerAsyncAopRunnerAndShutdown() throws Exception {
        ProcessResult result = runScenario("success");
        List<Event> events = result.events();

        assertOrder(events, "main-before", "before-all");
        assertOrder(events, "before-all", "after-container");
        assertOrder(events, "after-container", "onboot-enter");
        assertOrder(events, "main-return", "onboot-after-return");
        assertOrder(events, "onboot-after-return", "async-result:ok");
        assertOrder(events, "async-result:ok", "runner-start");
        assertOrder(events, "runner-start", "runner-complete");
        assertOrder(events, "runner-complete", "after-startup");
        assertOrder(events, "after-startup", "after-all");
        assertOrder(events, "after-all", "on-close");
        assertOrder(events, "on-close", "pre-destroy");
        assertOrder(events, "on-close", "async-service-pre-destroy");

        assertEquals("BootThread", event(events, "onboot-enter").thread());
        assertEquals("BootThread", event(events, "runner-start").thread());
        assertEquals("App-Scheduler-Worker", event(events, "schedule").thread());
        assertNotEquals("BootThread", event(events, "async-compute").thread());
        assertNotEquals("BootThread", event(events, "async-void").thread());
        assertEquals(1, count(events, "pre-destroy"));
        assertEquals(1, count(events, "async-service-pre-destroy"));
        assertFalse(hasEvent(events, "application-fail:"));
    }

    @Test
    void managedBootFailureRunsAfterAllAndApplicationFailButSkipsRunner() throws Exception {
        ProcessResult result = runScenario("failure");
        List<Event> events = result.events();

        assertOrder(events, "after-container", "onboot-fail");
        assertOrder(events, "onboot-fail", "after-all");
        assertOrder(events, "after-all", "application-fail:InvalidBootException");
        assertOrder(events, "application-fail:InvalidBootException", "on-close");
        assertOrder(events, "on-close", "pre-destroy");
        assertOrder(events, "on-close", "async-service-pre-destroy");
        assertFalse(hasEvent(events, "runner-start"));
        assertFalse(hasEvent(events, "after-startup"));
        assertEquals(1, count(events, "pre-destroy"));
        assertEquals(1, count(events, "async-service-pre-destroy"));
    }

    private ProcessResult runScenario(String scenario) throws Exception {
        Path eventsFile = tempDir.resolve(scenario + "-events.log");
        Path processLog = tempDir.resolve(scenario + "-process.log");
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        String classpath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path")
        );

        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp",
                classpath,
                ManagedIntegrationApp.class.getName(),
                eventsFile.toString(),
                scenario
        )
                .redirectErrorStream(true)
                .redirectOutput(processLog.toFile())
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }

        String output = Files.exists(processLog) ? Files.readString(processLog) : "";
        assertTrue(finished, () -> "JVM filha não terminou. Saída:\n" + output);
        assertEquals(0, process.exitValue(), () -> "JVM filha falhou. Saída:\n" + output);
        assertTrue(Files.exists(eventsFile), () -> "Arquivo de eventos ausente. Saída:\n" + output);

        List<Event> events = Files.readAllLines(eventsFile).stream()
                .map(Event::parse)
                .toList();
        return new ProcessResult(events, output);
    }

    private static void assertOrder(List<Event> events, String before, String after) {
        int beforeIndex = indexOf(events, before);
        int afterIndex = indexOf(events, after);
        assertTrue(beforeIndex >= 0, () -> "Evento ausente: " + before + " em " + events);
        assertTrue(afterIndex >= 0, () -> "Evento ausente: " + after + " em " + events);
        assertTrue(beforeIndex < afterIndex, () -> before + " deveria preceder " + after + ": " + events);
    }

    private static Event event(List<Event> events, String name) {
        return events.stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Evento ausente: " + name + " em " + events));
    }

    private static boolean hasEvent(List<Event> events, String prefix) {
        return events.stream().anyMatch(event -> event.name().startsWith(prefix));
    }

    private static long count(List<Event> events, String name) {
        return events.stream().filter(event -> event.name().equals(name)).count();
    }

    private static int indexOf(List<Event> events, String name) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record Event(int sequence, String name, String thread) {
        private static Event parse(String line) {
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Evento inválido: " + line);
            }
            return new Event(Integer.parseInt(parts[0]), parts[1], parts[2]);
        }
    }

    private record ProcessResult(List<Event> events, String output) {
    }
}
