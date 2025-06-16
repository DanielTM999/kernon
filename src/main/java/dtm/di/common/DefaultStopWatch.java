package dtm.di.common;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultStopWatch implements StopWatch{

    private final String name;
    private final Map<String, Long> laps;
    private long start;
    private long lastLap;
    private boolean running = false;

    public DefaultStopWatch(String name) {
        this.name = name;
        this.laps =  new LinkedHashMap<>();
    }

    /**
     * Inicia ou reinicia o cronômetro.
     */
    @Override
    public void start() {
        this.start = System.nanoTime();
        this.lastLap = start;
        this.laps.clear();
        this.running = true;
    }

    /**
     * Marca um ponto de medição com um nome.
     */
    @Override
    public void lap(String label) {
        ensureStarted();
        long now = System.nanoTime();
        laps.merge(label, now - lastLap, Long::sum);
        lastLap = now;
    }

    /**
     * Retorna o tempo total desde o início, em milissegundos.
     */
    @Override
    public double getTotalElapsedMillis() {
        ensureStarted();
        long total = System.nanoTime() - start;
        return total / 1_000_000.0;
    }

    /**
     * Retorna o tempo do lap específico em milissegundos.
     * Retorna -1 se não existir o lap.
     */
    @Override
    public double getLapElapsedMillis(String label) {
        ensureStarted();
        Long nano = laps.get(label);
        if (nano == null) return -1;;
        return nano / 1_000_000.0;
    }

    /**
     * Imprime o relatório de tempo no console (System.out).
     *
     * Esse método delega para {@link #print(PrintStream)} passando null,
     * o que faz com que a impressão seja feita no console.
     */
    @Override
    public void print() {
        print(null);
    }


    /**
     * Imprime o relatório de tempo no PrintStream fornecido.
     * Se o parâmetro for null, imprime no System.out.
     */
    @Override
    public void print(PrintStream out) {
        ensureStarted();
        if (out == null) out = System.out;

        long total = System.nanoTime() - start;

        out.println("======== [StopWatch] " + name + " ========");
        long cumulative = 0;
        for (Map.Entry<String, Long> entry : laps.entrySet()) {
            double millis = entry.getValue() / 1_000_000.0;
            cumulative += entry.getValue();
            double percentage = (entry.getValue() * 100.0) / total;
            out.printf(" - %-25s -> %7.3f ms (%5.2f%%)%n", entry.getKey(), millis, percentage);
        }
        out.println("-------------------------------------------");
        out.printf(" Total                            -> %7.3f ms (100%%)%n", total / 1_000_000.0);
        out.println("===========================================");
    }


    /**
     * Garante que o cronômetro foi iniciado antes de executar operações.
     */
    private void ensureStarted() {
        if (!running) {
            throw new IllegalStateException("StopWatch '" + name + "' has not been started. Call start() first.");
        }
    }

}
