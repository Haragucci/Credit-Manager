package op.creditmanager.client.gui.modern.recovery;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class RecoveryActionExecutor {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "CreditManager-Recovery-Action");
                thread.setDaemon(true);
                return thread;
            });

    private RecoveryActionExecutor() { }

    public static ExecutorService get() {
        return EXECUTOR;
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
    }

    public static boolean shutdownAndAwait(Duration timeout) {
        EXECUTOR.shutdown();
        try {
            return EXECUTOR.awaitTermination(Math.max(0L, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean isIdle() {
        return EXECUTOR.getActiveCount() == 0 && EXECUTOR.getQueue().isEmpty();
    }
}
