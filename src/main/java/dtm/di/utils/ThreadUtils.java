package dtm.di.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public final class ThreadUtils {

    private static final Map<Thread, Locker> lockMap = new ConcurrentHashMap<>();

    private ThreadUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Retorna uma String formatada contendo o relatório de todas as threads ativas.
     * Útil para logar ou imprimir no console para diagnóstico.
     * @return String com o relatório.
     */
    public static String getThreadReport() {
        StringBuilder report = new StringBuilder();
        Set<Thread> threadSet = Thread.getAllStackTraces().keySet();

        report.append("\n=== RELATÓRIO DE THREADS ATIVAS ===\n");

        report.append(String.format("%-5s | %-40s | %-15s | %-18s\n", "ID", "NOME", "ESTADO", "TIPO"));
        report.append("------------------------------------------------------------------------------------------\n");

        for (Thread t : threadSet) {
            String type = t.isDaemon() ? "DAEMON (OK)" : "USER (BLOQUEANTE)";

            report.append(String.format("%-5d | %-40s | %-15s | %-18s\n",
                    t.getId(),
                    truncate(t.getName(), 40),
                    t.getState(),
                    type));
        }
        report.append("=======================================\n");

        return report.toString();
    }

    public static void sleep(long duration, TimeUnit unit) {
        if (unit == null) {
            throw new IllegalArgumentException("TimeUnit não pode ser nulo");
        }
        sleep(unit.toMillis(duration));
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread interrompida durante o sleep", e);
        }
    }

    public static void lock() {
        lock(null);
    }

    public static void lock(Consumer<Thread> r) {
        Thread currentThread = Thread.currentThread();
        if(lockMap.containsKey(currentThread)) return;
        Locker locker = Locker.getNewLocker();
        lockMap.put(currentThread, locker);
        log.info("Travando thread: {}", currentThread.getName());
        new Thread(() -> {
            if(r != null) r.accept(currentThread);
        }).start();
        locker.lock();
        lockMap.remove(currentThread);
    }

    public static void unlock(Thread threadToUnlock) {
        if (threadToUnlock == null) return;
        Locker locker = lockMap.get(threadToUnlock);
        if(locker != null){
            log.debug("Destravando thread: {}", threadToUnlock.getName());
            locker.unlock();
        }else {
            log.warn("Tentativa de destravar thread que não estava travada: {}", threadToUnlock.getName());
        }
    }

    private static String truncate(String str, int width) {
        if (str == null) return "";
        if (str.length() > width) {
            return str.substring(0, width - 3) + "...";
        }
        return str;
    }

}
