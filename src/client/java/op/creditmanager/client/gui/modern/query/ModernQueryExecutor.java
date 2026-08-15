package op.creditmanager.client.gui.modern.query;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class ModernQueryExecutor {
    private static final Object LEGACY_OWNER = new Object();
    private static final LatestWinsQueryExecutor EXECUTOR = new LatestWinsQueryExecutor(16, "CreditManager-Query");
    private static final Executor LEGACY_EXECUTOR = command ->
            EXECUTOR.submitLatest(LEGACY_OWNER, () -> {
                command.run();
                return null;
            });

    private ModernQueryExecutor() { }
    public static Executor get() { return LEGACY_EXECUTOR; }
    public static <T> CompletableFuture<T> submitLatest(Object owner, Supplier<T> supplier) {
        return EXECUTOR.submitLatest(owner, supplier);
    }
    public static void cancel(Object owner) { EXECUTOR.cancel(owner); }
    public static void shutdown() { EXECUTOR.shutdownAndAwait(Duration.ZERO); }
    public static boolean shutdownAndAwait(Duration timeout) {
        return EXECUTOR.shutdownAndAwait(timeout);
    }
}
