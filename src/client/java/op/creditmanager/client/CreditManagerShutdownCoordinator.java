package op.creditmanager.client;

import op.creditmanager.client.core.CreditManagerMutationExecutor;
import op.creditmanager.client.gui.SkinHeadUtil;
import op.creditmanager.client.gui.modern.query.ModernQueryExecutor;
import op.creditmanager.client.gui.modern.recovery.RecoveryActionExecutor;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.time.Duration;

public final class CreditManagerShutdownCoordinator {
    private CreditManagerShutdownCoordinator() { }

    public static ShutdownResult shutdown() {
        return shutdown(new RuntimeShutdownServices(), new ShutdownTimeouts(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(4)));
    }

    static ShutdownResult shutdown(ShutdownServices services, ShutdownTimeouts timeouts) {
        boolean mutationsStopped = services.stopMutations(timeouts.mutations()) && services.mutationsIdle();
        boolean recoveryStopped = services.stopRecovery(timeouts.recovery()) && services.recoveryIdle();
        boolean backupFlushed = mutationsStopped && recoveryStopped
                && services.flushBackup(timeouts.backup());
        boolean queriesStopped = services.stopQueries(timeouts.queries());
        boolean skinFlushed = services.stopSkins(timeouts.skins());
        boolean leaseReleased = backupFlushed && queriesStopped && skinFlushed && services.releaseStorage();
        return new ShutdownResult(mutationsStopped, recoveryStopped, backupFlushed,
                queriesStopped, skinFlushed, leaseReleased);
    }

    interface ShutdownServices {
        boolean stopMutations(Duration timeout);
        boolean mutationsIdle();
        boolean stopRecovery(Duration timeout);
        boolean recoveryIdle();
        boolean flushBackup(Duration timeout);
        boolean stopQueries(Duration timeout);
        boolean stopSkins(Duration timeout);
        boolean releaseStorage();
    }

    record ShutdownTimeouts(Duration mutations, Duration recovery, Duration backup,
                            Duration queries, Duration skins) { }

    public record ShutdownResult(boolean mutationsStopped, boolean recoveryStopped,
                                 boolean backupFlushed, boolean queriesStopped,
                                 boolean skinFlushed, boolean leaseReleased) { }

    private static final class RuntimeShutdownServices implements ShutdownServices {
        @Override
        public boolean stopMutations(Duration timeout) {
            return CreditManagerMutationExecutor.getInstance().shutdownAndAwait(timeout);
        }

        @Override
        public boolean mutationsIdle() {
            return CreditManagerMutationExecutor.getInstance().isIdle();
        }

        @Override
        public boolean stopRecovery(Duration timeout) {
            return RecoveryActionExecutor.shutdownAndAwait(timeout);
        }

        @Override
        public boolean recoveryIdle() {
            return RecoveryActionExecutor.isIdle();
        }

        @Override
        public boolean flushBackup(Duration timeout) {
            return DatabaseManager.getInstance().flushBackupCheckpoint(timeout);
        }

        @Override
        public boolean stopQueries(Duration timeout) {
            return ModernQueryExecutor.shutdownAndAwait(timeout);
        }

        @Override
        public boolean stopSkins(Duration timeout) {
            return SkinHeadUtil.shutdownAndAwait(timeout);
        }

        @Override
        public boolean releaseStorage() {
            return DatabaseManager.getInstance().releaseStorageAfterCheckpoint();
        }
    }
}
