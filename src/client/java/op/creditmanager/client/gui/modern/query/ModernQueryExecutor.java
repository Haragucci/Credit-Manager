package op.creditmanager.client.gui.modern.query;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ModernQueryExecutor {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "CreditManager-Query");
                thread.setDaemon(true);
                return thread;
            });

    private ModernQueryExecutor() { }
    public static ExecutorService get() { return EXECUTOR; }
    public static void shutdown() { EXECUTOR.shutdown(); }
    public static boolean shutdownAndAwait(Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        EXECUTOR.shutdown();
        try {
            if (EXECUTOR.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return true;
            EXECUTOR.shutdownNow();
            return EXECUTOR.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
