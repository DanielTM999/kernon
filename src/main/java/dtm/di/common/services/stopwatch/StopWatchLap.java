package dtm.di.common.services.stopwatch;

public interface StopWatchLap {

    /**
     * Tempo Inicial: Tempo do início (Start).
     */
    long startTime();

    /**
     * Tempo Do Lap: Tempo do Lap
     */
    long lapTime();

    /**
     * @return Duração apenas desta volta (Delta).
     * (TotalTime - LapStartTime).
     */
    long lapTimeDuration();

    /**
     * Tempo do Último Lap ate o momento
     */
    long lapStartTime();

    /**
     * @return Tempo total decorrido desde o Start até este Lap.
     */
    long getTotalTime();

    String tag();
}

