package op.creditmanager.client.gui.modern.query;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ModernQueryExecutor {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CreditManager-Query");
        thread.setDaemon(true);
        return thread;
    });

    private ModernQueryExecutor() { }
    public static ExecutorService get() { return EXECUTOR; }
}
