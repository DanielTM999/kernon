package dtm.di.common.services.stopwatch.Imple;

import dtm.di.common.services.stopwatch.StopWatch;
import dtm.di.common.services.stopwatch.StopWatchLap;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultStopWatch implements StopWatch {
    private final ReentrantLock lock = new ReentrantLock();

    private long startTimeNanos = 0;

    private boolean running = false;
    private final List<StopWatchLap> laps = new ArrayList<>();


    @Override
    public void start() {
        lock.lock();
        try {
            if (!running) {
                this.startTimeNanos = System.nanoTime();
                this.running = true;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            if (running) {
                this.running = false;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            this.startTimeNanos = 0;
            this.running = false;
            this.laps.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getTotalTime() {
        lock.lock();
        try {
            return System.nanoTime() - startTimeNanos;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public StopWatchLap lap() {
        return lap(null);
    }

    @Override
    public StopWatchLap lap(String tag) {
        lock.lock();
        if(!running) throw new RuntimeException("StopWatch unstarted");
        try {
            long now = System.nanoTime();

            long lastLapEndTime = (startTimeNanos == 0) ? now : startTimeNanos;
            if (!laps.isEmpty()) {
                lastLapEndTime = laps.getLast().lapTime();
            }

            long lapDuration = now - lastLapEndTime;

            StopWatchLap lap = new StopWatchLapImpl(
                    startTimeNanos,
                    now,
                    lapDuration,
                    lastLapEndTime,
                    tag
            );

            laps.add(lap);
            return lap;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<StopWatchLap> getLaps() {
        lock.lock();
        try {
            return new ArrayList<>(laps);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isRunning() {
        lock.lock();
        try {
            return running;
        } finally {
            lock.unlock();
        }
    }

    private record StopWatchLapImpl(long startTime, long lapTime, long lapTimeDuration, long lapStartTime, String tag) implements StopWatchLap {

        private StopWatchLapImpl(long startTime, long lapTime, long lapTimeDuration, long lapStartTime, String tag) {
                this.startTime = startTime;
                this.lapTime = lapTime;
                this.lapTimeDuration = lapTimeDuration;
                this.lapStartTime = lapStartTime;
                this.tag = (tag == null || tag.isEmpty()) ? "Lap" : tag;
            }

            @Override
            public long getTotalTime() {
                return lapTime() - startTime();
            }

            @NonNull
            @Override
            public String toString() {
                return String.format("Lap[tag='%s', duration=%dns, total=%dns]", tag, lapTimeDuration, lapTime);
            }

        }

}
