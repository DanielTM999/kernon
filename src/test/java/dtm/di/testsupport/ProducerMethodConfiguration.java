package dtm.di.testsupport;

import dtm.di.annotations.Async;
import dtm.di.annotations.Component;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;
import dtm.di.prototypes.async.AsyncComponent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Configuration
@Profile("producer-integration")
public class ProducerMethodConfiguration {

    private static volatile CountDownLatch started = new CountDownLatch(1);
    private static volatile CountDownLatch release = new CountDownLatch(1);

    public static void resetAsyncProducer() {
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    public static boolean awaitStarted() throws InterruptedException {
        return started.await(5, TimeUnit.SECONDS);
    }

    public static void releaseAsyncProducer() {
        release.countDown();
    }

    @Async
    @Component
    public AsyncBean asyncBean() {
        Probe.record("MainAsyncProducer.thread:" + Thread.currentThread().getName());
        started.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout aguardando liberação do produtor async");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("produtor async interrompido", e);
        }
        Probe.record("MainAsyncProducer.completed");
        return new AsyncBean("async");
    }

    @Async
    @Component
    public SecondaryAsyncBean secondaryAsyncBean() {
        Probe.record("SecondaryAsyncProducer.completed");
        return new SecondaryAsyncBean("secondary");
    }

    @Component
    public DependentBean dependentBean(AsyncComponent<AsyncBean> asyncBean) {
        Probe.record("MainAsyncProducer.dependent");
        return new DependentBean(asyncBean);
    }

    @Profile("disabled-profile")
    @Component
    public InactiveBean inactiveBean() {
        Probe.record("MainProfileProducer.inactive");
        return new InactiveBean();
    }

    public record AsyncBean(String value) {
    }

    public record SecondaryAsyncBean(String value) {
    }

    public record DependentBean(AsyncComponent<AsyncBean> asyncBean) {
    }

    public static final class InactiveBean {
    }
}
