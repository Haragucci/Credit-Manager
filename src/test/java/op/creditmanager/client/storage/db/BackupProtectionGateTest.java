package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupProtectionGateTest {
    @TempDir Path temporary;

    @Test
    void criticalStateBlocksOnlyNormalWritesAndReopensWithoutRestart() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            coordinator.updateBackupProtection(BackupCheckpointService.ProtectionState.CRITICAL);

            CreditEntry blocked = new CreditEntry(UUID.randomUUID(), "blocked", "alice", "bob", 1_000L, null, null);
            assertFalse(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(blocked, List.of(), List.of(), List.of())));
            assertFalse(coordinator.isSafeForWrites());
            assertEquals(DatabaseManager.DatabaseAvailability.BACKUP_PROTECTION_CRITICAL, coordinator.availability());

            coordinator.updateBackupProtection(BackupCheckpointService.ProtectionState.LOCAL_ONLY);
            assertTrue(coordinator.isSafeForWrites());
            assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(blocked, List.of(), List.of(), List.of())));
        }
    }

    @Test
    void revisionAndTimeGraceBecomeCriticalAndVerifiedRecoveryClearsIt() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        BackupCheckpointService service = new BackupCheckpointService(
                () -> BackupCheckpointService.CheckpointResult.failed(true), ignored -> { }, clock::get);
        try {
            service.seed(0L, 0L, clock.get(), 0L, clock.get(), true);
            service.request(10L);
            assertEquals(BackupCheckpointService.ProtectionState.DEGRADED, service.metrics().protectionState());
            service.request(11L);
            assertEquals(BackupCheckpointService.ProtectionState.CRITICAL, service.metrics().protectionState());

            service.acceptExternalResult(BackupCheckpointService.CheckpointResult.of(true, true, true, 11L, clock.get()));
            assertEquals(BackupCheckpointService.ProtectionState.HEALTHY, service.metrics().protectionState());

            service.request(12L);
            clock.addAndGet(BackupCheckpointService.MAX_UNPROTECTED_MILLIS + 1L);
            assertEquals(BackupCheckpointService.ProtectionState.CRITICAL, service.metrics().protectionState());
        } finally {
            service.stopNow();
        }
    }
}
