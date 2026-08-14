package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.MultiProcessStorageLeaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardKillBackupRecoveryTest {
    @TempDir Path temporary;

    @Test
    void partialMirrorCopyIsIgnoredAndVerifiedBackupRestoresAfterHardKill() throws Exception {
        Path primary = temporary.resolve("primary");
        Path mirror = temporary.resolve("mirror");
        UUID creditId = UUID.randomUUID();
        Process child = MultiProcessStorageLeaseIntegrationTest.start(DatabaseCrashChildMain.class,
                primary.toString(), mirror.toString(), creditId.toString());
        try {
            MultiProcessStorageLeaseIntegrationTest.awaitLine(child, "BACKUP_CONFIRMED", Duration.ofSeconds(20));
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            deleteTree(primary);
            try (StorageTestScope scope = new StorageTestScope()) {
                scope.configurePrimary(primary, mirror);
                DatabaseCoordinator coordinator = new DatabaseCoordinator();
                coordinator.initialize();
                assertEquals(DatabaseManager.DatabaseAvailability.MISSING_DATABASE, coordinator.availability());
                assertTrue(coordinator.restoreLatestValidBackup());
                DatabaseManager.DatabaseState state = coordinator.loadRuntimeCreditState();
                assertEquals(creditId, state.credits().getFirst().getId());
                assertEquals(1, new BackupMirrorService(mirror, new RecoveryFileOps()).candidateFiles().size());
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
