package dtm.di.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class Probe {

    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, AtomicInteger> COUNTERS = Collections.synchronizedMap(new LinkedHashMap<>());

    private Probe() {
    }

    public static void record(String event) {
        EVENTS.add(event);
        COUNTERS.computeIfAbsent(event, key -> new AtomicInteger()).incrementAndGet();
    }

    public static List<String> events() {
        synchronized (EVENTS) {
            return new ArrayList<>(EVENTS);
        }
    }

    public static int count(String event) {
        AtomicInteger counter = COUNTERS.get(event);
        return counter == null ? 0 : counter.get();
    }

    public static boolean contains(String event) {
        return count(event) > 0;
    }

    public static void reset() {
        EVENTS.clear();
        COUNTERS.clear();
    }
}
