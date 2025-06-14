package dtm.di.common;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class StopWatch {

    private final String name;
    private final long start;
    private long lastLap;
    private final Map<String, Long> laps = new LinkedHashMap<>();

    public StopWatch(String name) {
        this.name = name;
        this.start = System.nanoTime();
        this.lastLap = start;
    }

    /**
     * Marca um ponto de medição com um nome.
     */
    public void lap(String label) {
        long now = System.nanoTime();
        laps.put(label, now - lastLap);
        lastLap = now;
    }

    /**
     * Retorna o tempo total desde o início, em milissegundos.
     */
    public double getTotalElapsedMillis() {
        long total = System.nanoTime() - start;
        return total / 1_000_000.0;
    }

    /**
     * Retorna o tempo do lap específico em milissegundos.
     * Retorna -1 se não existir o lap.
     */
    public double getLapElapsedMillis(String label) {
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
    public void print() {
        print(null);
    }


    /**
     * Imprime o relatório de tempo no PrintStream fornecido.
     * Se o parâmetro for null, imprime no System.out.
     */
    public void print(PrintStream out) {
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

}
