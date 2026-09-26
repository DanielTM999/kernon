package dtm.di.integration.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class EventRecorder {

    private static final Object FILE_LOCK = new Object();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static volatile Path output;
    private static volatile String scenario;
    private static volatile Thread mainThread;

    private EventRecorder() {
    }

    public static void configure(String[] args) {
        output = Path.of(args[0]);
        scenario = args.length > 1 ? args[1] : "";
        mainThread = Thread.currentThread();
    }

    public static String scenario() {
        return scenario;
    }

    public static boolean isScenario(String expected) {
        return expected.equals(scenario);
    }

    public static boolean onMainThread() {
        return Thread.currentThread() == mainThread;
    }

    public static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("espera interrompida", e);
        }
    }

    public static void record(String event) {
        Path target = output;
        if (target == null) {
            return;
        }

        String line = "%03d|%s|%s|%s%n".formatted(
                SEQUENCE.incrementAndGet(),
                event,
                Thread.currentThread().getName(),
                onMainThread()
        );

        synchronized (FILE_LOCK) {
            try {
                Files.writeString(
                        target,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                throw new IllegalStateException("falha ao registrar evento " + event, e);
            }
        }
    }
}
