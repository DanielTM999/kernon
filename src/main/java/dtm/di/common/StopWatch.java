package dtm.di.common;

import java.io.PrintStream;

public interface StopWatch {
    void start();

    void lap(String label);

    double getTotalElapsedMillis();

    double getLapElapsedMillis(String label);

    void print();

    void print(PrintStream out);
}
