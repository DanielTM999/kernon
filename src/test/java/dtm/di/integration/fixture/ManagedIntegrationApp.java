package dtm.di.integration.fixture;

import dtm.di.annotations.Async;
import dtm.di.annotations.Component;
import dtm.di.annotations.PreDestroy;
import dtm.di.annotations.Service;
import dtm.di.annotations.Singleton;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.annotations.aop.EnableAsync;
import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.annotations.boot.LifecycleHook;
import dtm.di.annotations.boot.OnApplicationFail;
import dtm.di.annotations.boot.OnBoot;
import dtm.di.annotations.schedule.EnableSchedule;
import dtm.di.annotations.schedule.Schedule;
import dtm.di.annotations.schedule.ScheduleMethod;
import dtm.di.application.ApplicationRunner;
import dtm.di.application.startup.ManagedApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationBoot(ManagedIntegrationApp.Boot.class)
public final class ManagedIntegrationApp {

    private ManagedIntegrationApp() {
    }

    public static void main(String[] args) {
        Recorder.configure(Path.of(args[0]), args.length > 1 ? args[1] : "success");
        Recorder.record("main-before");
        ManagedApplication.doRun(false, args);
        Recorder.record("main-return");
        Recorder.DO_RUN_RETURNED.countDown();
    }

    @EnableAsync
    @EnableSchedule(threads = 2)
    public static final class Boot {

        private Boot() {
        }

        @LifecycleHook(LifecycleHook.Event.BEFORE_ALL)
        public static void beforeAll() {
            Recorder.record("before-all");
        }

        @LifecycleHook(LifecycleHook.Event.AFTER_CONTAINER_LOAD)
        public static void afterContainerLoad() {
            Recorder.record("after-container");
        }

        @OnBoot
        public static void onBoot(ManagedAsyncService asyncService) throws Exception {
            Recorder.record("onboot-enter");
            Recorder.await(Recorder.DO_RUN_RETURNED, "doRun não retornou");
            Recorder.record("onboot-after-return");

            if (Recorder.isFailureScenario()) {
                Recorder.record("onboot-fail");
                throw new IllegalStateException("integration-boot-failure");
            }

            CompletableFuture<String> result = asyncService.compute();
            asyncService.fireAndForget();
            Recorder.record("async-result:" + result.get(5, TimeUnit.SECONDS));
        }

        @LifecycleHook(LifecycleHook.Event.AFTER_STARTUP_METHOD)
        public static void afterStartup() {
            Recorder.record("after-startup");
        }

        @LifecycleHook(LifecycleHook.Event.AFTER_ALL)
        public static void afterAll() {
            Recorder.record("after-all");
        }

        @LifecycleHook(LifecycleHook.Event.ON_CLOSE)
        public static void onClose() {
            Recorder.record("on-close");
        }

        @OnApplicationFail
        public static void onFailure(Throwable error, Thread thread) {
            Recorder.record("application-fail:" + error.getClass().getSimpleName());
        }
    }

    @Singleton
    @Service
    public static class ManagedAsyncService {

        @Async
        public CompletableFuture<String> compute() {
            Recorder.record("async-compute");
            return CompletableFuture.completedFuture("ok");
        }

        @Async
        public void fireAndForget() {
            Recorder.record("async-void");
            Recorder.ASYNC_VOID_COMPLETED.countDown();
        }

        @PreDestroy
        public void destroy() {
            Recorder.record("async-service-pre-destroy");
        }
    }

    @Singleton
    @Service
    public static class ManagedRunner implements ApplicationRunner {

        @Override
        public void run(String[] args) {
            Recorder.record("runner-start");
            Recorder.await(Recorder.ASYNC_VOID_COMPLETED, "@Async void não concluiu");
            Recorder.await(Recorder.SCHEDULE_COMPLETED, "schedule não executou");
            Recorder.record("runner-complete");
        }
    }

    @Schedule
    public static class ScheduledJob {

        @ScheduleMethod(startDelay = 10, timeUnit = TimeUnit.MILLISECONDS, periodic = false)
        public void execute() {
            Recorder.record("schedule");
            Recorder.SCHEDULE_COMPLETED.countDown();
        }
    }

    @Singleton
    @Component
    @DisableAop
    public static class ManagedLifecycleBean {

        @PreDestroy
        public void destroy() {
            Recorder.record("pre-destroy");
        }
    }

    private static final class Recorder {

        private static final Object FILE_LOCK = new Object();
        private static final AtomicInteger SEQUENCE = new AtomicInteger();
        private static final CountDownLatch DO_RUN_RETURNED = new CountDownLatch(1);
        private static final CountDownLatch ASYNC_VOID_COMPLETED = new CountDownLatch(1);
        private static final CountDownLatch SCHEDULE_COMPLETED = new CountDownLatch(1);

        private static volatile Path output;
        private static volatile String scenario;

        private Recorder() {
        }

        private static void configure(Path outputFile, String selectedScenario) {
            output = outputFile;
            scenario = selectedScenario;
        }

        private static boolean isFailureScenario() {
            return "failure".equals(scenario);
        }

        private static void await(CountDownLatch latch, String message) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("espera interrompida", e);
            }
        }

        private static void record(String event) {
            Path target = output;
            if (target == null) {
                return;
            }

            String line = "%03d|%s|%s%n".formatted(
                    SEQUENCE.incrementAndGet(),
                    event,
                    Thread.currentThread().getName()
            );

            synchronized (FILE_LOCK) {
                try {
                    Files.writeString(
                            target,
                            line,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                    );
                } catch (IOException e) {
                    throw new IllegalStateException("falha ao registrar evento " + event, e);
                }
            }
        }
    }
}
