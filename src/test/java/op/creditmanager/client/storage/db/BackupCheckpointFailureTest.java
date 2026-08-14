package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.DataHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BackupCheckpointFailureTest {
    @TempDir Path temporary;

    @Test
    void pendingCheckpointDoesNotPublishDegradedWarningBeforeARealFailure() {
        CopyOnWriteArrayList<BackupCheckpointService.ProtectionState> changes =
                new CopyOnWriteArrayList<>();

        BackupCheckpointService service =
                new BackupCheckpointService(
                        () -> BackupCheckpointService.CheckpointResult.success(
                                1L,
                                System.currentTimeMillis()
                        ),
                        changes::add
                );

        try {
            service.seed(
                    0L,
                    0L,
                    System.currentTimeMillis(),
                    0L,
                    System.currentTimeMillis(),
                    true
            );

            changes.clear();

            service.request(1L);

            assertEquals(
                    BackupCheckpointService.ProtectionState.DEGRADED,
                    service.metrics().protectionState()
            );

            assertFalse(
                    changes.contains(
                            BackupCheckpointService.ProtectionState.DEGRADED
                    )
            );
        } finally {
            service.stopNow();
        }
    }

    @Test
    void degradedProtectionWarnsWithoutInvalidatingWritesAndClearsAfterRecovery() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();

            coordinator.updateBackupProtection(true);

            assertEquals(DatabaseManager.DatabaseAvailability.BACKUP_PROTECTION_DEGRADED, coordinator.availability());
            assertTrue(coordinator.isHealthy());
            assertTrue(coordinator.isSafeForWrites());
            assertTrue(DataHealth.consumeWarning().contains("Änderungen sind gespeichert"));
            assertFalse(DataHealth.reasons().isEmpty());
            coordinator.updateBackupProtection(false);
            assertEquals(DatabaseManager.DatabaseAvailability.HEALTHY, coordinator.availability());
            assertTrue(DataHealth.reasons().isEmpty());
        }
    }

    @Test
    void repeatedFailureDegradesProtectionWithoutChangingCommitTruthAndThenRecovers() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CopyOnWriteArrayList<BackupCheckpointService.ProtectionState> changes = new CopyOnWriteArrayList<>();
        boolean committed = true;
        BackupCheckpointService service = new BackupCheckpointService(() -> {
            int attempt = attempts.incrementAndGet();
            return attempt <= 3 ? BackupCheckpointService.CheckpointResult.failed()
                    : BackupCheckpointService.CheckpointResult.success(1L, System.currentTimeMillis());
        }, changes::add);
        try {
            service.seed(1L, 0L, System.currentTimeMillis());
            service.request(1L);

            assertTrue(service.awaitIdle(Duration.ofSeconds(10)));
            assertTrue(committed);
            assertTrue(attempts.get() >= 4);
            assertEquals(List.of(BackupCheckpointService.ProtectionState.DEGRADED,
                    BackupCheckpointService.ProtectionState.HEALTHY), changes);
            assertFalse(service.metrics().protectionDegraded());
            assertEquals(1L, service.metrics().latestHealthyBackupRevision());
        } finally {
            service.stopNow();
        }
    }

    @Test
    void persistentFailureKeepsShutdownBoundedAndReportsUnprotectedRevision() {
        BackupCheckpointService service = new BackupCheckpointService(BackupCheckpointService.CheckpointResult::failed, ignored -> { });
        service.seed(2L, 1L, System.currentTimeMillis());
        service.request(2L);
        long started = System.nanoTime();

        boolean protectedState = service.flushAndShutdown(Duration.ofMillis(200));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertFalse(protectedState);
        assertTrue(elapsedMillis < 2_000L);
        assertEquals(1L, service.metrics().backupLagRevisions());
    }
}
