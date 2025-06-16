package dtm.di.common;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ConcurrentStopWatch implements StopWatch{

    private final String name;
    private final ConcurrentHashMap<String, AtomicLong> laps = new ConcurrentHashMap<>();
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong lastLapTime = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ConcurrentStopWatch(String name) {
        this.name = name;
    }

    @Override
    public void start() {
        long now = System.nanoTime();
        startTime.set(now);
        lastLapTime.set(now);
        laps.clear();
        running.set(true);
    }

    @Override
    public void lap(String label) {
        ensureStarted();
        long now = System.nanoTime();
        // obtém o tempo do último lap e atualiza para o agora
        long previousLap = lastLapTime.getAndSet(now);
        long delta = now - previousLap;

        // acumula o delta para o label
        laps.computeIfAbsent(label, k -> new AtomicLong(0)).addAndGet(delta);
    }

    @Override
    public double getTotalElapsedMillis() {
        ensureStarted();
        long now = System.nanoTime();
        return (now - startTime.get()) / 1_000_000.0;
    }

    @Override
    public double getLapElapsedMillis(String label) {
        ensureStarted();
        AtomicLong nano = laps.get(label);
        if (nano == null) return -1;
        return nano.get() / 1_000_000.0;
    }

    @Override
    public void print() {
        print(null);
    }

    @Override
    public void print(PrintStream out) {
        ensureStarted();
        if (out == null) out = System.out;

        long total = System.nanoTime() - startTime.get();

        out.println("======== [StopWatch] " + name + " ========");
        for (Map.Entry<String, AtomicLong> entry : laps.entrySet()) {
            double millis = entry.getValue().get() / 1_000_000.0;
            double percentage = (entry.getValue().get() * 100.0) / total;
            out.printf(" - %-25s -> %7.3f ms (%5.2f%%)%n", entry.getKey(), millis, percentage);
        }
        out.println("-------------------------------------------");
        out.printf(" Total                            -> %7.3f ms (100%%)%n", total / 1_000_000.0);
        out.println("===========================================");
    }

    private void ensureStarted() {
        if (!running.get()) {
            throw new IllegalStateException("StopWatch '" + name + "' has not been started. Call start() first.");
        }
    }
}
