package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.StorageRootResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualHealthyBackupTest {
    @TempDir Path temporary;

    @Test
    void mirrorRestoresExactDataAfterEntirePrimaryRootDisappears() throws Exception {
        Path primary = temporary.resolve("primary");
        Path mirror = temporary.resolve("independent-mirror");
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(primary, mirror);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            UUID creditId = UUID.randomUUID();
            CreditEntry credit = new CreditEntry(creditId, "mirror-exact", "alice", "bob", 12_345L, null, "note");
            assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));

            DatabaseManager.ManualBackupResult backup = coordinator.createHealthyBackupNow();
            assertTrue(backup.localSuccess());
            assertTrue(backup.mirrorSuccess());
            assertTrue(Files.isRegularFile(backup.localArtifact()));
            assertTrue(Files.isRegularFile(backup.mirrorArtifact()));
            assertEquals(DatabaseManager.BackupSource.LOCAL_AND_MIRROR,
                    coordinator.listAvailableBackups().stream().filter(value -> value.entry().revision() == backup.revision())
                            .findFirst().orElseThrow().source());

            deleteTree(primary);
            DatabaseCoordinator afterLoss = new DatabaseCoordinator();
            afterLoss.initialize();
            assertEquals(DatabaseManager.DatabaseAvailability.MISSING_DATABASE, afterLoss.availability());
            assertFalse(Files.exists(FileManager.getDatabaseStorageFile()));

            assertTrue(afterLoss.restoreLatestValidBackup());
            DatabaseManager.DatabaseState restored = afterLoss.loadRuntimeCreditState();
            assertEquals(1, restored.credits().size());
            assertEquals(creditId, restored.credits().getFirst().getId());
            assertEquals("mirror-exact", restored.credits().getFirst().getDealName());
            assertEquals(12_345L, restored.credits().getFirst().getAmountMinor());
        }
    }

    @Test
    void localSuccessIsReportedHonestlyWhenMirrorPublicationFails() throws Exception {
        Path primary = temporary.resolve("local-only-primary");
        Path blockedMirror = temporary.resolve("blocked-mirror");
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(primary, blockedMirror);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            Files.writeString(blockedMirror.resolve("backups"), "not-a-directory");
            DatabaseManager.ManualBackupResult result = coordinator.createHealthyBackupNow();
            assertTrue(result.localSuccess());
            assertFalse(result.mirrorSuccess());
            assertTrue(Files.isRegularFile(result.localArtifact()));
            assertTrue(result.message().contains("lokal"));
        }
    }

    @Test
    void secondaryManualBackupIsRejectedWithoutOpeningH2() throws Exception {
        Path primary = temporary.resolve("secondary-primary");
        StorageRootResolver.StorageLocation location = new StorageRootResolver.StorageLocation(primary, null,
                StorageRootResolver.StorageEnvironment.STANDARD, "secondary",
                StorageRootResolver.ResolutionSource.STANDARD_GAME_DIR, true, List.of());
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configure(primary, location, FileManager.StorageAccessState.SECONDARY_INSTANCE);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            DatabaseManager.ManualBackupResult result = coordinator.createHealthyBackupNow();
            assertFalse(result.localSuccess());
            assertFalse(result.mirrorSuccess());
            assertFalse(Files.exists(FileManager.getDatabaseStorageFile()));
        }
    }

    private void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
