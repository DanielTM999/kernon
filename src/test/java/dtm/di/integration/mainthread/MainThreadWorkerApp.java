package dtm.di.integration.mainthread;

import dtm.di.annotations.Component;
import dtm.di.annotations.PreDestroy;
import dtm.di.annotations.RunOnMainThread;
import dtm.di.annotations.Service;
import dtm.di.annotations.Singleton;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.annotations.boot.EnableMainThreadWorker;
import dtm.di.annotations.boot.LifecycleHook;
import dtm.di.annotations.boot.OnApplicationFail;
import dtm.di.annotations.boot.OnBoot;
import dtm.di.application.startup.ManagedApplication;
import dtm.di.application.worker.MainThreadWorker;
import dtm.di.exceptions.MainThreadWorkerAccessException;
import dtm.di.integration.support.EventRecorder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class MainThreadWorkerApp {

    private MainThreadWorkerApp() {
    }

    private static void launch(String[] args, Class<?> mainClass) {
        EventRecorder.configure(args);
        EventRecorder.record("main-before");
        ManagedApplication.doRun(false, args, mainClass);
        EventRecorder.record("main-return");
    }

    @ApplicationBoot(WorkerBoot.class)
    public static final class WorkerMain {

        private WorkerMain() {
        }

        public static void main(String[] args) {
            launch(args, WorkerMain.class);
        }
    }

    @ApplicationBoot(AopDisabledBoot.class)
    public static final class AopDisabledMain {

        private AopDisabledMain() {
        }

        public static void main(String[] args) {
            launch(args, AopDisabledMain.class);
        }
    }

    @ApplicationBoot(StaticDeniedBoot.class)
    public static final class StaticDeniedMain {

        private StaticDeniedMain() {
        }

        public static void main(String[] args) {
            launch(args, StaticDeniedMain.class);
        }
    }

    @EnableMainThreadWorker(staticCaller = true)
    public static final class WorkerBoot {

        private WorkerBoot() {
        }

        @LifecycleHook(LifecycleHook.Event.BEFORE_ALL)
        public static void beforeAll() {
            EventRecorder.record("before-all-static:" + (ManagedApplication.getMainThreadWorker() != null));
        }

        @OnBoot
        public static void onBoot(MainThreadWorker worker, GameLike game, MainThreadService service) throws Exception {
            EventRecorder.record("onboot-enter");
            EventRecorder.record("boot-is-main:" + worker.isMainThread());

            switch (EventRecorder.scenario()) {
                case "success" -> success(worker, game);
                case "boot-failure" -> {
                    EventRecorder.record("onboot-fail");
                    throw new IllegalStateException("main-thread-boot-failure");
                }
                case "double-shutdown" -> doubleShutdown(worker);
                case "system-exit" -> worker.runOnMainThread(() -> {
                    EventRecorder.record("exit-task");
                    System.exit(0);
                });
                case "static-caller" -> {
                    EventRecorder.record("static-same:" + (ManagedApplication.getMainThreadWorker() == worker));
                    worker.runOnMainThread(ManagedApplication::shutdown);
                }
                case "aop" -> aop(worker, service);
                default -> throw new IllegalStateException("cenário desconhecido: " + EventRecorder.scenario());
            }
        }

        private static void success(MainThreadWorker worker, GameLike game) throws Exception {
            worker.runOnMainThread(() -> EventRecorder.record("run-on-main"));

            Long window = worker.callOnMainThread(() -> {
                EventRecorder.record("call-body");
                return 1280L * 720L;
            }).get(10, TimeUnit.SECONDS);
            EventRecorder.record("call-result:" + window);

            try {
                worker.callOnMainThread(() -> {
                    throw new IllegalArgumentException("boom");
                }).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                EventRecorder.record("call-error:" + e.getCause().getClass().getSimpleName());
            }

            worker.runAndAwaitOnMainThread(() -> EventRecorder.record("run-and-await"));
            EventRecorder.record("after-await");
            EventRecorder.record("game-same-worker:" + (game.worker() == worker));

            worker.runOnMainThread(game::run);
        }

        private static void doubleShutdown(MainThreadWorker worker) {
            worker.runAndAwaitOnMainThread(() -> {
                ManagedApplication.shutdown();
                ManagedApplication.shutdown();
                worker.shutdown();
                EventRecorder.record("shutdown-requested");
            });
            try {
                worker.runOnMainThread(() -> EventRecorder.record("late-task"));
                EventRecorder.record("late-accepted");
            } catch (RejectedExecutionException e) {
                EventRecorder.record("late-rejected");
            }
            ManagedApplication.shutdown();
        }

        private static void aop(MainThreadWorker worker, MainThreadService service) throws Exception {
            service.touch();
            Long window = service.createWindow().get(10, TimeUnit.SECONDS);
            EventRecorder.record("aop-future:" + window);
            EventRecorder.record("aop-sync:" + service.syncValue());

            worker.runOnMainThread(() -> {
                CompletableFuture<Long> inline = service.createWindow();
                EventRecorder.record("aop-inline-done:" + inline.isDone());
                ManagedApplication.shutdown();
            });
        }

        @LifecycleHook(LifecycleHook.Event.AFTER_ALL)
        public static void afterAll() {
            EventRecorder.record("after-all");
        }

        @LifecycleHook(LifecycleHook.Event.ON_CLOSE)
        public static void onClose() {
            EventRecorder.record("on-close");
        }

        @OnApplicationFail
        public static void onFailure(Throwable error, Thread thread) {
            EventRecorder.record("application-fail:" + error.getClass().getSimpleName());
        }
    }

    @DisableAop
    @EnableMainThreadWorker(staticCaller = true)
    public static final class AopDisabledBoot {

        private AopDisabledBoot() {
        }

        @OnBoot
        public static void onBoot(MainThreadWorker worker, MainThreadService service) {
            EventRecorder.record("onboot-enter");
            service.touch();
            worker.runOnMainThread(() -> {
                EventRecorder.record("worker-task");
                ManagedApplication.shutdown();
            });
        }

        @LifecycleHook(LifecycleHook.Event.ON_CLOSE)
        public static void onClose() {
            EventRecorder.record("on-close");
        }
    }

    @EnableMainThreadWorker
    public static final class StaticDeniedBoot {

        private StaticDeniedBoot() {
        }

        @OnBoot
        public static void onBoot(MainThreadWorker worker) {
            try {
                ManagedApplication.getMainThreadWorker();
                EventRecorder.record("static:available");
            } catch (MainThreadWorkerAccessException e) {
                EventRecorder.record("static:" + e.getReason());
            }
            worker.runOnMainThread(ManagedApplication::shutdown);
        }

        @LifecycleHook(LifecycleHook.Event.ON_CLOSE)
        public static void onClose() {
            EventRecorder.record("on-close");
        }
    }

    @Singleton
    @Service
    public static class GameLike {

        private final MainThreadWorker worker;

        public GameLike(MainThreadWorker worker) {
            this.worker = worker;
            EventRecorder.record("game-constructed:" + (worker != null));
        }

        public MainThreadWorker worker() {
            return worker;
        }

        public void run() {
            EventRecorder.record("game-run");
            EventRecorder.record("game-is-main:" + worker.isMainThread());
            ManagedApplication.shutdown();
            EventRecorder.record("game-after-shutdown");
        }
    }

    @Singleton
    @Service
    public static class MainThreadService {

        @RunOnMainThread
        public void touch() {
            EventRecorder.record("aop-void");
        }

        @RunOnMainThread
        public CompletableFuture<Long> createWindow() {
            EventRecorder.record("aop-future-body");
            return CompletableFuture.completedFuture(42L);
        }

        @RunOnMainThread
        public int syncValue() {
            EventRecorder.record("aop-sync-body");
            return 7;
        }
    }

    @Singleton
    @Component
    @DisableAop
    public static class LoadProbe {

        public LoadProbe() {
            if (EventRecorder.isScenario("load-failure")) {
                EventRecorder.record("load-fail");
                throw new IllegalStateException("main-thread-load-failure");
            }
        }

        @PreDestroy
        public void destroy() {
            EventRecorder.record("pre-destroy");
        }
    }
}
