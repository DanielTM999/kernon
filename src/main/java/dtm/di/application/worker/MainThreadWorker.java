package dtm.di.application.worker;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public interface MainThreadWorker {

    void runOnMainThread(Runnable task);

    void runAndAwaitOnMainThread(Runnable task);

    <T> CompletableFuture<T> callOnMainThread(Callable<T> task);

    boolean isMainThread();

    void shutdown();

    void stop();

    boolean isShutdown();

    boolean isTerminated();

    MainThreadWorkerState getState();

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
}
