package dtm.di.application.worker;

import dtm.di.application.worker.impl.DefaultMainThreadWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMainThreadWorkerTest {

    private static final long TIMEOUT_SECONDS = 5;

    private final List<Throwable> handledErrors = new CopyOnWriteArrayList<>();
    private DefaultMainThreadWorker worker;
    private Thread owner;

    @AfterEach
    void tearDown() throws Exception {
        if (worker == null) {
            return;
        }
        worker.stop();
        if (owner.isAlive()) {
            assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            owner.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
    }

    private void createWorker() {
        owner = new Thread(() -> worker.runLoop(), "worker-owner");
        owner.setDaemon(true);
        worker = new DefaultMainThreadWorker(owner, (thread, error) -> handledErrors.add(error));
    }

    private void startWorker() {
        createWorker();
        owner.start();
    }

    private CountDownLatch blockMainThread(CountDownLatch started, List<String> order, AtomicInteger interruptedFlags) {
        CountDownLatch release = new CountDownLatch(1);
        worker.runOnMainThread(() -> {
            order.add("A-start");
            started.countDown();
            await(release);
            if (Thread.currentThread().isInterrupted()) {
                interruptedFlags.incrementAndGet();
            }
            order.add("A");
        });
        return release;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout aguardando latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("runOnMainThread executa na thread dona do worker")
    void runOnMainThreadExecutesOnOwnerThread() throws Exception {
        startWorker();
        CompletableFuture<Thread> executedBy = new CompletableFuture<>();

        worker.runOnMainThread(() -> executedBy.complete(Thread.currentThread()));

        assertSame(owner, executedBy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("isMainThread compara a instância real da Thread")
    void isMainThreadComparesThreadInstance() throws Exception {
        startWorker();

        assertFalse(worker.isMainThread());
        assertTrue(worker.callOnMainThread(worker::isMainThread).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertSame(owner, worker.getOwnerThread());
    }

    @Test
    @DisplayName("callOnMainThread retorna o valor da task")
    void callOnMainThreadReturnsValue() throws Exception {
        startWorker();

        CompletableFuture<Long> future = worker.callOnMainThread(() -> 1280L * 720L);

        assertEquals(921_600L, future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("callOnMainThread completa o future excepcionalmente")
    void callOnMainThreadPropagatesException() {
        startWorker();
        IllegalStateException failure = new IllegalStateException("glfw");

        CompletableFuture<Object> future = worker.callOnMainThread(() -> {
            throw failure;
        });

        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertSame(failure, error.getCause());
        assertTrue(handledErrors.isEmpty());
    }

    @Test
    @DisplayName("chamadas feitas pela própria main thread executam imediatamente")
    void callsFromMainThreadRunInline() throws Exception {
        startWorker();
        List<String> order = new ArrayList<>();

        CompletableFuture<Boolean> inlineCompleted = worker.callOnMainThread(() -> {
            order.add("outer-start");
            worker.runOnMainThread(() -> order.add("inline-run"));
            worker.runAndAwaitOnMainThread(() -> order.add("inline-await"));
            CompletableFuture<String> inner = worker.callOnMainThread(() -> "inner");
            order.add("outer-end");
            return inner.isDone() && "inner".equals(inner.join());
        });

        assertTrue(inlineCompleted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(List.of("outer-start", "inline-run", "inline-await", "outer-end"), order);
    }

    @Test
    @DisplayName("runAndAwaitOnMainThread bloqueia o chamador até a task terminar")
    void runAndAwaitBlocksUntilTaskCompletes() throws Exception {
        startWorker();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = blockMainThread(started, order, new AtomicInteger());
        await(started);

        CompletableFuture<Void> caller = CompletableFuture.runAsync(() -> {
            worker.runAndAwaitOnMainThread(() -> order.add("B"));
            order.add("caller-resumed");
        });

        assertFalse(caller.isDone());
        release.countDown();
        caller.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of("A-start", "A", "B", "caller-resumed"), order);
    }

    @Test
    @DisplayName("runAndAwaitOnMainThread relança a exceção original")
    void runAndAwaitRethrowsOriginalException() {
        startWorker();
        IllegalArgumentException failure = new IllegalArgumentException("init");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> worker.runAndAwaitOnMainThread(() -> {
                    throw failure;
                }));

        assertSame(failure, thrown);
    }

    @Test
    @DisplayName("shutdown executa toda a fila e termina")
    void shutdownDrainsQueue() throws Exception {
        startWorker();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = blockMainThread(started, order, new AtomicInteger());
        await(started);
        worker.runOnMainThread(() -> order.add("B"));
        worker.runOnMainThread(() -> order.add("C"));
        CompletableFuture<String> d = worker.callOnMainThread(() -> {
            order.add("D");
            return "D";
        });

        worker.shutdown();
        assertEquals(MainThreadWorkerState.SHUTTING_DOWN, worker.getState());
        assertTrue(worker.isShutdown());
        assertFalse(worker.isTerminated());
        release.countDown();

        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(List.of("A-start", "A", "B", "C", "D"), order);
        assertEquals("D", d.get());
        assertEquals(MainThreadWorkerState.TERMINATED, worker.getState());
        assertTrue(worker.isTerminated());
    }

    @Test
    @DisplayName("stop descarta a fila sem interromper a task em execução")
    void stopDiscardsQueueWithoutInterruptingCurrentTask() throws Exception {
        startWorker();
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger interruptedFlags = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = blockMainThread(started, order, interruptedFlags);
        await(started);
        worker.runOnMainThread(() -> order.add("B"));
        worker.runOnMainThread(() -> order.add("C"));
        CompletableFuture<String> d = worker.callOnMainThread(() -> {
            order.add("D");
            return "D";
        });

        worker.stop();
        assertEquals(MainThreadWorkerState.STOPPING, worker.getState());
        assertTrue(d.isCancelled());
        release.countDown();

        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(List.of("A-start", "A"), order);
        assertEquals(0, interruptedFlags.get());
        assertEquals(MainThreadWorkerState.TERMINATED, worker.getState());
    }

    @Test
    @DisplayName("runAndAwaitOnMainThread nunca fica preso quando o worker é parado")
    void runAndAwaitNeverHangsWhenWorkerStops() throws Exception {
        createWorker();
        CompletableFuture<Void> caller = CompletableFuture.runAsync(
                () -> worker.runAndAwaitOnMainThread(() -> { }),
                runnable -> new Thread(runnable).start()
        );

        worker.stop();

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> caller.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof CancellationException
                || error.getCause() instanceof RejectedExecutionException, error::toString);
        owner.start();
        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("tasks são rejeitadas após shutdown")
    void rejectsTasksAfterShutdown() throws Exception {
        startWorker();
        worker.shutdown();

        assertRejectsEverything();
        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertRejectsEverything();
    }

    @Test
    @DisplayName("tasks são rejeitadas após stop")
    void rejectsTasksAfterStop() throws Exception {
        startWorker();
        worker.stop();

        assertRejectsEverything();
        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertRejectsEverything();
    }

    private void assertRejectsEverything() {
        assertThrows(RejectedExecutionException.class, () -> worker.runOnMainThread(() -> { }));
        assertThrows(RejectedExecutionException.class, () -> worker.runAndAwaitOnMainThread(() -> { }));
        assertThrows(RejectedExecutionException.class, () -> worker.callOnMainThread(() -> "x"));
    }

    @Test
    @DisplayName("tasks enviadas antes do loop iniciar ficam na fila e executam")
    void tasksSubmittedBeforeLoopStartAreExecuted() throws Exception {
        createWorker();
        assertEquals(MainThreadWorkerState.NEW, worker.getState());

        CompletableFuture<Thread> executedBy = worker.callOnMainThread(Thread::currentThread);
        owner.start();

        assertSame(owner, executedBy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(MainThreadWorkerState.RUNNING, worker.getState());
    }

    @Test
    @DisplayName("exceção em runOnMainThread vai para o handler e o worker continua")
    void fireAndForgetFailureIsHandledAndLoopContinues() throws Exception {
        startWorker();
        IllegalStateException failure = new IllegalStateException("render");

        worker.runOnMainThread(() -> {
            throw failure;
        });

        assertEquals("alive", worker.callOnMainThread(() -> "alive").get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(List.of(failure), handledErrors);
        assertEquals(MainThreadWorkerState.RUNNING, worker.getState());
    }

    @Test
    @DisplayName("submits concorrentes com shutdown: toda task aceita executa e nenhuma é perdida")
    void concurrentSubmitAndShutdownNeverLosesAcceptedTasks() throws Exception {
        startWorker();
        int producers = 8;
        int tasksPerProducer = 500;
        CyclicBarrier barrier = new CyclicBarrier(producers + 1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int producer = 0; producer < producers; producer++) {
            futures.add(CompletableFuture.runAsync(() -> {
                awaitBarrier(barrier);
                for (int task = 0; task < tasksPerProducer; task++) {
                    try {
                        worker.runOnMainThread(executed::incrementAndGet);
                        accepted.incrementAndGet();
                    } catch (RejectedExecutionException e) {
                        rejected.incrementAndGet();
                    }
                }
            }, runnable -> new Thread(runnable).start()));
        }

        awaitBarrier(barrier);
        worker.shutdown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(producers * tasksPerProducer, accepted.get() + rejected.get());
        assertEquals(accepted.get(), executed.get());
    }

    @Test
    @DisplayName("shutdown e stop concorrentes e repetidos são seguros")
    void repeatedAndConcurrentShutdownIsIdempotent() throws Exception {
        startWorker();
        int callers = 8;
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int index = 0; index < callers; index++) {
            boolean useStop = index % 2 == 0;
            futures.add(CompletableFuture.runAsync(() -> {
                awaitBarrier(barrier);
                for (int repeat = 0; repeat < 10; repeat++) {
                    if (useStop) {
                        worker.stop();
                    } else {
                        worker.shutdown();
                    }
                }
            }, runnable -> new Thread(runnable).start()));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        worker.shutdown();
        worker.stop();
        assertEquals(MainThreadWorkerState.TERMINATED, worker.getState());
    }

    @Test
    @DisplayName("runLoop só pode ser executado uma vez e pela thread dona")
    void runLoopRequiresOwnerThreadAndSingleExecution() {
        startWorker();

        assertThrows(IllegalStateException.class, () -> worker.runLoop());

        CompletableFuture<Object> reentrant = worker.callOnMainThread(() -> {
            worker.runLoop();
            return null;
        });
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> reentrant.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    @DisplayName("awaitTermination pela main thread ativa é recusado para evitar deadlock")
    void awaitTerminationFromMainThreadIsRejected() {
        startWorker();

        CompletableFuture<Boolean> future = worker.callOnMainThread(() -> worker.awaitTermination(1, TimeUnit.SECONDS));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    @DisplayName("interrupção da main thread ociosa encerra o worker e preserva a flag")
    void interruptWhileIdleStopsWorker() throws Exception {
        CompletableFuture<Boolean> interruptFlagAfterLoop = new CompletableFuture<>();
        owner = new Thread(() -> {
            worker.runLoop();
            interruptFlagAfterLoop.complete(Thread.currentThread().isInterrupted());
        }, "worker-owner");
        owner.setDaemon(true);
        worker = new DefaultMainThreadWorker(owner, (thread, error) -> handledErrors.add(error));
        owner.start();
        worker.callOnMainThread(() -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        owner.interrupt();

        assertTrue(worker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(interruptFlagAfterLoop.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
