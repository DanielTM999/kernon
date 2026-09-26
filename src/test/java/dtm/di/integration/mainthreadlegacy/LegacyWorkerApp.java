package dtm.di.integration.mainthreadlegacy;

import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.annotations.boot.LifecycleHook;
import dtm.di.annotations.boot.OnBoot;
import dtm.di.application.startup.ManagedApplication;
import dtm.di.application.worker.MainThreadWorker;
import dtm.di.core.DependencyContainer;
import dtm.di.exceptions.MainThreadWorkerAccessException;
import dtm.di.integration.support.EventRecorder;

import java.util.concurrent.CountDownLatch;

@ApplicationBoot(LegacyWorkerApp.Boot.class)
public final class LegacyWorkerApp {

    private static final CountDownLatch DO_RUN_RETURNED = new CountDownLatch(1);

    private LegacyWorkerApp() {
    }

    public static void main(String[] args) {
        EventRecorder.configure(args);
        EventRecorder.record("main-before");
        ManagedApplication.doRun(false, args, LegacyWorkerApp.class);
        EventRecorder.record("main-return");
        DO_RUN_RETURNED.countDown();
    }

    public static final class Boot {

        private Boot() {
        }

        @OnBoot
        public static void onBoot(DependencyContainer container) {
            EventRecorder.record("onboot-enter");
            EventRecorder.await(DO_RUN_RETURNED, "doRun não retornou");
            EventRecorder.record("onboot-after-return");

            try {
                ManagedApplication.getMainThreadWorker();
                EventRecorder.record("static:available");
            } catch (MainThreadWorkerAccessException e) {
                EventRecorder.record("static:" + e.getReason());
            }

            try {
                Object worker = container.getDependency(MainThreadWorker.class);
                EventRecorder.record("di:" + (worker == null ? "absent" : "present"));
            } catch (RuntimeException e) {
                EventRecorder.record("di:absent");
            }
        }

        @LifecycleHook(LifecycleHook.Event.ON_CLOSE)
        public static void onClose() {
            EventRecorder.record("on-close");
        }
    }
}
