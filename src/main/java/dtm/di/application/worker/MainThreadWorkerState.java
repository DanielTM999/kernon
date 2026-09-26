package dtm.di.application.worker;

public enum MainThreadWorkerState {
    NEW,
    RUNNING,
    SHUTTING_DOWN,
    STOPPING,
    TERMINATED;

    public boolean isAcceptingTasks() {
        return this == NEW || this == RUNNING;
    }
}
