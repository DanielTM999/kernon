package dtm.di.common.services.stopwatch;

import java.util.List;

public interface StopWatch {

    /**
     * Inicia a contagem do tempo.
     */
    void start();

    /**
     * Para a contagem do tempo.
     */
    void stop();

    /**
     * Reseta o cronômetro e limpa as medições.
     */
    void reset();

    /**
     * Retorna o tempo total em nanosegundos.
     */
    long getTotalTime();

    /**
     * Registra uma volta sem tag (usa um padrão ou null).
     */
    StopWatchLap lap();

    /**
     * Registra uma volta associando uma TAG (descrição).
     * @param tag Nome ou descrição da volta (ex: "Checkpoint 1", "Fim do Loop")
     */
    StopWatchLap lap(String tag);

    /**
     * Retorna a lista de objetos Lap (que contém tempo e tag).
     */
    List<StopWatchLap> getLaps();

    /**
     * Retorna o estado do cronometro
     */
    boolean isRunning();

}

