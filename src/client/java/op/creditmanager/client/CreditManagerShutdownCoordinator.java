package op.creditmanager.client;

import op.creditmanager.client.gui.modern.query.ModernQueryExecutor;
import op.creditmanager.client.gui.modern.recovery.RecoveryActionExecutor;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.time.Duration;

public final class CreditManagerShutdownCoordinator {
    private CreditManagerShutdownCoordinator() { }

    public static ShutdownResult shutdown() {
        boolean queriesStopped = ModernQueryExecutor.shutdownAndAwait(Duration.ofSeconds(2));
        boolean recoveryStopped = RecoveryActionExecutor.shutdownAndAwait(Duration.ofSeconds(5));
        if (!recoveryStopped || !RecoveryActionExecutor.isIdle()) {
            return new ShutdownResult(queriesStopped, false, false, false);
        }
        boolean backupFlushed = DatabaseManager.getInstance().shutdown(Duration.ofSeconds(5));
        return new ShutdownResult(queriesStopped, true, backupFlushed, backupFlushed);
    }

    public record ShutdownResult(boolean queriesStopped, boolean recoveryStopped,
                                 boolean backupFlushed, boolean leaseReleased) { }
}
