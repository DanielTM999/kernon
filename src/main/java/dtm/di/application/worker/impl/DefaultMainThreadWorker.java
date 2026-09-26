package dtm.di.application.worker.impl;

import dtm.di.application.worker.MainThreadWorker;
import dtm.di.application.worker.MainThreadWorkerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

public class DefaultMainThreadWorker implements MainThreadWorker {
    private static final Logger logger = LoggerFactory.getLogger(DefaultMainThreadWorker.class);

    private final Thread owner;
    private final BiConsumer<Thread, Throwable> errorHandler;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition terminated = lock.newCondition();
    private final Deque<Task> queue = new ArrayDeque<>();
    private volatile MainThreadWorkerState state = MainThreadWorkerState.NEW;
    private boolean loopStarted;

    public DefaultMainThreadWorker(Thread owner, BiConsumer<Thread, Throwable> errorHandler) {
        this.owner = Objects.requireNonNull(owner, "owner não pode ser null");
        this.errorHandler = (errorHandler != null) ? errorHandler : DefaultMainThreadWorker::logUncaught;
    }

    public Thread getOwnerThread() {
        return owner;
    }

    @Override
    public void runOnMainThread(Runnable task) {
        Objects.requireNonNull(task, "task não pode ser null");

        if(isMainThread()){
            ensureAccepting();
            task.run();
            return;
        }

        enqueue(new Task(task, null));
    }

    @Override
    public void runAndAwaitOnMainThread(Runnable task) {
        Objects.requireNonNull(task, "task não pode ser null");

        if(isMainThread()){
            ensureAccepting();
            task.run();
            return;
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        enqueue(new Task(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        }, completion));

        awaitCompletion(completion);
    }

    @Override
    public <T> CompletableFuture<T> callOnMainThread(Callable<T> task) {
        Objects.requireNonNull(task, "task não pode ser null");

        CompletableFuture<T> result = new CompletableFuture<>();

        if(isMainThread()){
            ensureAccepting();
            completeWith(result, task);
            return result;
        }

        enqueue(new Task(() -> completeWith(result, task), result));
        return result;
    }

    @Override
    public boolean isMainThread() {
        return Thread.currentThread() == owner;
    }

    @Override
    public void shutdown() {
        lock.lock();
        try {
            if(state.isAcceptingTasks()){
                state = MainThreadWorkerState.SHUTTING_DOWN;
                notEmpty.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        List<Task> discarded;
        lock.lock();
        try {
            if(state == MainThreadWorkerState.STOPPING || state == MainThreadWorkerState.TERMINATED) return;
            state = MainThreadWorkerState.STOPPING;
            discarded = drainQueue();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        cancelAll(discarded);
    }

    @Override
    public boolean isShutdown() {
        return !state.isAcceptingTasks();
    }

    @Override
    public boolean isTerminated() {
        return state == MainThreadWorkerState.TERMINATED;
    }

    @Override
    public MainThreadWorkerState getState() {
        return state;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit não pode ser null");
        long nanos = unit.toNanos(timeout);

        lock.lock();
        try {
            if(state != MainThreadWorkerState.TERMINATED && isMainThread()){
                throw new IllegalStateException("awaitTermination não pode ser chamado pela main thread enquanto o MainThreadWorker está ativo");
            }
            while (state != MainThreadWorkerState.TERMINATED) {
                if(nanos <= 0L) return false;
                nanos = terminated.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void runLoop() {
        if(!isMainThread()){
            throw new IllegalStateException("runLoop deve ser executado pela thread dona do MainThreadWorker: " + owner.getName());
        }

        lock.lock();
        try {
            if(loopStarted){
                throw new IllegalStateException("runLoop já foi executado para este MainThreadWorker");
            }
            loopStarted = true;
            if(state == MainThreadWorkerState.NEW){
                state = MainThreadWorkerState.RUNNING;
            }
        } finally {
            lock.unlock();
        }

        boolean interrupted = false;
        try {
            while (true) {
                Task task;
                lock.lock();
                try {
                    while (queue.isEmpty() && state == MainThreadWorkerState.RUNNING) {
                        try {
                            notEmpty.await();
                        } catch (InterruptedException e) {
                            interrupted = true;
                            state = MainThreadWorkerState.STOPPING;
                        }
                    }

                    if(state == MainThreadWorkerState.STOPPING) break;

                    task = queue.pollFirst();
                    if(task == null) break;
                } finally {
                    lock.unlock();
                }

                execute(task);
            }
        } finally {
            terminate();
            if(interrupted) Thread.currentThread().interrupt();
        }
    }

    private void enqueue(Task task) {
        lock.lock();
        try {
            if(!state.isAcceptingTasks()){
                throw rejected();
            }
            queue.addLast(task);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    private void ensureAccepting() {
        if(!state.isAcceptingTasks()){
            throw rejected();
        }
    }

    private RejectedExecutionException rejected() {
        return new RejectedExecutionException("MainThreadWorker não aceita novas tasks no estado " + state);
    }

    private void execute(Task task) {
        try {
            task.body().run();
        } catch (Throwable throwable) {
            try {
                errorHandler.accept(owner, throwable);
            } catch (Throwable handlerError) {
                handlerError.addSuppressed(throwable);
                logUncaught(owner, handlerError);
            }
        }
    }

    private void terminate() {
        List<Task> leftovers;
        lock.lock();
        try {
            state = MainThreadWorkerState.TERMINATED;
            leftovers = drainQueue();
            terminated.signalAll();
        } finally {
            lock.unlock();
        }
        cancelAll(leftovers);
    }

    private List<Task> drainQueue() {
        List<Task> drained = new ArrayList<>(queue);
        queue.clear();
        return drained;
    }

    private static void cancelAll(List<Task> tasks) {
        for (Task task : tasks) {
            if(task.completion() != null){
                task.completion().cancel(false);
            }
        }
    }

    private static <T> void completeWith(CompletableFuture<T> result, Callable<T> task) {
        try {
            result.complete(task.call());
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
        }
    }

    private static void awaitCompletion(CompletableFuture<?> completion) {
        try {
            completion.join();
        } catch (CompletionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if(cause instanceof RuntimeException runtimeException) throw runtimeException;
            if(cause instanceof Error error) throw error;
            throw e;
        }
    }

    private static void logUncaught(Thread thread, Throwable throwable) {
        logger.error("Erro não tratado em task do MainThreadWorker na thread {}", thread.getName(), throwable);
    }

    private record Task(Runnable body, CompletableFuture<?> completion) {}
}
