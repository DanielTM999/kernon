package dtm.di.prototypes;

@FunctionalInterface
public interface ThrowableAction {
    void run() throws Throwable;
}
