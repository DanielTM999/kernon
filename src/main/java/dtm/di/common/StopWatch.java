package dtm.di.common;

public class StopWatch {
    private final String name;
    private long start;
    private long end;

    public StopWatch(String name) {
        this.name = name;
        start();
    }

    public void start() {
        this.start = System.nanoTime();
        this.end = 0;
    }

    public void stop() {
        this.end = System.nanoTime();
    }

    public double getElapsedMillis() {
        long effectiveEnd = (end == 0) ? System.nanoTime() : end;
        return (effectiveEnd - start) / 1_000_000.0;
    }

    public void print() {
        stop();
        System.out.printf("[StopWatch] %s -> %.3f ms%n", name, getElapsedMillis());
    }
}
