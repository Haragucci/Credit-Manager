package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BackupCheckpointServiceTest {
    @TempDir Path temporary;

    @Test
    void committedRevisionProducesAValidatedH2CheckpointManifest() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            CreditEntry credit = new CreditEntry(UUID.randomUUID(), "checkpoint-credit", "alice", "bob", 10_000L, null, null);
            DatabaseManager.MutationCommitReceipt receipt = coordinator.commitCreditMutationWithReceipt(
                    new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of()));
            assertTrue(receipt.committed());
            BackupCheckpointService service = new BackupCheckpointService(coordinator::createBackupCheckpoint,
                    coordinator::updateBackupProtection);
            try {
                service.seed(receipt.revision(), -1L, 0L);
                service.request(receipt.revision());

                assertTrue(service.awaitIdle(Duration.ofSeconds(10)));
                DatabaseManager.BackupManifestEntry backup = coordinator.listBackups().stream()
                        .filter(DatabaseManager.BackupManifestEntry::automaticRestoreEligible)
                        .findFirst().orElseThrow();
                assertTrue(backup.revision() >= receipt.revision());
                assertEquals(1, backup.creditCount());
                assertEquals(0L, service.metrics().backupLagRevisions());
            } finally {
                service.stopNow();
            }
        }
    }

    @Test
    void rapidMutationBurstIsCoalescedAndFinalRevisionIsProtected() throws Exception {
        AtomicInteger checkpoints = new AtomicInteger();
        AtomicLong committedRevision = new AtomicLong();
        BackupCheckpointService service = new BackupCheckpointService(() -> {
            checkpoints.incrementAndGet();
            return BackupCheckpointService.CheckpointResult.success(committedRevision.get(), System.currentTimeMillis());
        }, ignored -> { });
        try {
            service.seed(0L, 0L, System.currentTimeMillis());
            for (int revision = 1; revision <= 10; revision++) {
                committedRevision.set(revision);
                service.request(revision);
            }

            assertTrue(service.awaitIdle(Duration.ofSeconds(5)));
            BackupCheckpointService.Metrics metrics = service.metrics();
            assertEquals(10L, metrics.currentRevision());
            assertEquals(10L, metrics.latestHealthyBackupRevision());
            assertEquals(0L, metrics.backupLagRevisions());
            assertTrue(checkpoints.get() >= 1 && checkpoints.get() <= 2);
        } finally {
            service.stopNow();
        }
    }

    @Test
    void freshStartupRequestsAnInitialHealthyCheckpoint() throws Exception {
        AtomicInteger checkpoints = new AtomicInteger();
        BackupCheckpointService service = new BackupCheckpointService(() -> {
            checkpoints.incrementAndGet();
            return BackupCheckpointService.CheckpointResult.success(0L, System.currentTimeMillis());
        }, ignored -> { });
        try {
            service.seed(0L, -1L, 0L);
            service.request(0L);

            assertTrue(service.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(1, checkpoints.get());
            assertEquals(0L, service.metrics().latestHealthyBackupRevision());
        } finally {
            service.stopNow();
        }
    }

    @Test
    void boundedShutdownFlushesDirtyRevision() {
        AtomicLong revision = new AtomicLong(4L);
        BackupCheckpointService service = new BackupCheckpointService(() ->
                BackupCheckpointService.CheckpointResult.success(revision.get(), System.currentTimeMillis()), ignored -> { });
        service.seed(4L, 3L, System.currentTimeMillis());
        service.request(4L);

        assertTrue(service.flushAndShutdown(Duration.ofSeconds(3)));
        assertEquals(4L, service.metrics().latestHealthyBackupRevision());
    }
}
