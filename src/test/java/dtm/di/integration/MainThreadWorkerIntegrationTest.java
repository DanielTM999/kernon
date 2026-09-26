package dtm.di.integration;

import dtm.di.integration.mainthread.MainThreadWorkerApp;
import dtm.di.integration.mainthreadlegacy.LegacyWorkerApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainThreadWorkerIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void withoutAnnotationDoRunReturnsAndNoWorkerIsRegistered() throws Exception {
        List<Event> events = runScenario(LegacyWorkerApp.class, "legacy");

        assertOrder(events, "main-return", "onboot-after-return");
        assertOrder(events, "onboot-after-return", "on-close");
        assertTrue(hasEvent(events, "static:NOT_ENABLED"));
        assertTrue(hasEvent(events, "di:absent"));
        assertEquals("BootThread", event(events, "onboot-enter").thread());
        assertEquals(1, count(events, "on-close"));
    }

    @Test
    void tasksRunOnRealMainThreadAndDoRunBlocksUntilApplicationShutdown() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.WorkerMain.class, "success");

        assertTrue(event(events, "before-all-static:true").main());
        assertTrue(hasEvent(events, "game-constructed:true"));
        assertOrder(events, "game-constructed:true", "onboot-enter");
        assertFalse(event(events, "onboot-enter").main());
        assertTrue(hasEvent(events, "boot-is-main:false"));

        assertTrue(event(events, "run-on-main").main());
        assertTrue(event(events, "call-body").main());
        assertTrue(hasEvent(events, "call-result:921600"));
        assertTrue(hasEvent(events, "call-error:IllegalArgumentException"));
        assertTrue(event(events, "run-and-await").main());
        assertOrder(events, "run-and-await", "after-await");
        assertTrue(hasEvent(events, "game-same-worker:true"));

        assertTrue(event(events, "game-run").main());
        assertTrue(hasEvent(events, "game-is-main:true"));
        assertOrder(events, "game-same-worker:true", "game-run");
        assertTrue(hasEvent(events, "after-all"));
        assertOrder(events, "game-run", "game-after-shutdown");
        assertOrder(events, "game-after-shutdown", "on-close");
        assertOrder(events, "on-close", "pre-destroy");
        assertOrder(events, "pre-destroy", "main-return");
        assertTrue(event(events, "on-close").main());
        assertTrue(event(events, "main-return").main());
        assertEquals(1, count(events, "on-close"));
        assertEquals(1, count(events, "pre-destroy"));
        assertFalse(hasEvent(events, "application-fail:"));
    }

    @Test
    void bootFailureReleasesMainThread() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.WorkerMain.class, "boot-failure");

        assertOrder(events, "onboot-fail", "after-all");
        assertOrder(events, "after-all", "application-fail:InvalidBootException");
        assertTrue(hasEvent(events, "main-return"));
        assertOrder(events, "application-fail:InvalidBootException", "on-close");
        assertEquals(1, count(events, "on-close"));
        assertEquals(1, count(events, "pre-destroy"));
    }

    @Test
    void containerLoadFailureReleasesMainThread() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.WorkerMain.class, "load-failure");

        assertOrder(events, "load-fail", "application-fail:InvalidBootException");
        assertFalse(hasEvent(events, "onboot-enter"));
        assertTrue(hasEvent(events, "main-return"));
        assertEquals(1, count(events, "on-close"));
    }

    @Test
    void repeatedShutdownClosesApplicationOnce() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.WorkerMain.class, "double-shutdown");

        assertTrue(hasEvent(events, "shutdown-requested"));
        assertTrue(hasEvent(events, "late-rejected"));
        assertFalse(hasEvent(events, "late-task"));
        assertOrder(events, "shutdown-requested", "on-close");
        assertOrder(events, "on-close", "main-return");
        assertEquals(1, count(events, "on-close"));
        assertEquals(1, count(events, "pre-destroy"));
    }

    @Test
    void systemExitFromMainThreadTaskRunsShutdownHookOnce() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.WorkerMain.class, "system-exit");

        assertTrue(event(events, "exit-task").main());
        assertOrder(events, "exit-task", "on-close");
        assertEquals("GracefulShutdownHook", event(events, "on-close").thread());
        assertEquals(1, count(events, "on-close"));
        assertEquals(1, count(events, "pre-destroy"));
        assertFalse(hasEvent(events, "main-return"));
    }

    @Test
    void staticCallerExposesSameInstanceAsDependencyInjection() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.WorkerMain.class, "static-caller");

        assertTrue(hasEvent(events, "static-same:true"));
        assertOrder(events, "on-close", "main-return");
    }

    @Test
    void staticAccessIsDeniedByDefault() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.StaticDeniedMain.class, "static-denied");

        assertTrue(hasEvent(events, "static:STATIC_ACCESS_DISABLED"));
        assertOrder(events, "on-close", "main-return");
    }

    @Test
    void runOnMainThreadAnnotationDispatchesThroughAop() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.WorkerMain.class, "aop");

        assertTrue(event(events, "aop-void").main());
        assertTrue(hasEvent(events, "aop-future:42"));
        assertTrue(hasEvent(events, "aop-sync:7"));
        assertTrue(hasEvent(events, "aop-inline-done:true"));
        assertTrue(events.stream()
                .filter(item -> item.name().equals("aop-future-body") || item.name().equals("aop-sync-body"))
                .allMatch(Event::main));
        assertEquals(2, count(events, "aop-future-body"));
        assertOrder(events, "aop-inline-done:true", "on-close");
        assertOrder(events, "on-close", "main-return");
    }

    @Test
    void workerWorksWhenAopIsDisabled() throws Exception {
        List<Event> events = runScenario(MainThreadWorkerApp.AopDisabledMain.class, "aop-disabled");

        assertFalse(event(events, "aop-void").main());
        assertEquals("BootThread", event(events, "aop-void").thread());
        assertTrue(event(events, "worker-task").main());
        assertOrder(events, "worker-task", "on-close");
        assertOrder(events, "on-close", "main-return");
    }

    private List<Event> runScenario(Class<?> mainClass, String scenario) throws Exception {
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
                mainClass.getName(),
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

        return Files.readAllLines(eventsFile).stream()
                .map(Event::parse)
                .toList();
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

    private record Event(int sequence, String name, String thread, boolean main) {
        private static Event parse(String line) {
            String[] parts = line.split("\\|", 4);
            if (parts.length != 4) {
                throw new IllegalArgumentException("Evento inválido: " + line);
            }
            return new Event(Integer.parseInt(parts[0]), parts[1], parts[2], Boolean.parseBoolean(parts[3]));
        }
    }
}
