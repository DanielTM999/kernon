package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Singleton;

import java.util.concurrent.atomic.AtomicInteger;

@Singleton
@Component
public class MainCounter {

    private final AtomicInteger value = new AtomicInteger();

    public int increment() {
        return value.incrementAndGet();
    }

    public int value() {
        return value.get();
    }

    public String describe() {
        return "main-counter";
    }
}
