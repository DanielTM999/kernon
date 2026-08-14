package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.PreDestroy;
import dtm.di.annotations.Singleton;
import dtm.di.annotations.aop.DisableAop;

import java.util.concurrent.atomic.AtomicInteger;

@Singleton
@Component
@DisableAop
public class MainPreDestroyProbe {

    private static final AtomicInteger DESTROY_COUNT = new AtomicInteger();

    @PreDestroy
    public void destroy() {
        DESTROY_COUNT.incrementAndGet();
    }

    public static void reset() {
        DESTROY_COUNT.set(0);
    }

    public static int destroyCount() {
        return DESTROY_COUNT.get();
    }
}
