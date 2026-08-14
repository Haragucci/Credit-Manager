package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.dao.DatabaseMetadataDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMissingSafetyTest {
    @TempDir Path temporary;

    @Test
    void disappearingActiveDatabaseIsTypedAndNeverRecreatedByNormalAccess() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            Path active = FileManager.getDatabaseStorageFile();
            Path retained = temporary.resolve("retained-original.mv.db");
            Files.move(active, retained);

            assertThrows(IllegalStateException.class, coordinator::loadCreditState);

            assertEquals(DatabaseManager.DatabaseAvailability.MISSING_DATABASE, coordinator.availability());
            assertTrue(coordinator.isWriteLocked());
            assertFalse(Files.exists(active));
            assertTrue(Files.isRegularFile(retained));
            assertTrue(Files.isRegularFile(FileManager.getStorageIdentityFile()));
        }
    }

    @Test
    void priorIdentityPreventsFreshBootstrapWhenDatabaseIsMissing() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator first = new DatabaseCoordinator();
            first.initialize();
            Files.move(FileManager.getDatabaseStorageFile(), temporary.resolve("preserved.mv.db"));

            DatabaseCoordinator restarted = new DatabaseCoordinator();
            restarted.initialize();

            assertEquals(DatabaseManager.DatabaseAvailability.MISSING_DATABASE, restarted.availability());
            assertFalse(Files.exists(FileManager.getDatabaseStorageFile()));
        }
    }

    @Test
    void mismatchedSidecarLocksStorageUntilOriginalIdentityIsRestored() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            UUID creditId = UUID.randomUUID();
            DatabaseCoordinator first = new DatabaseCoordinator();
            first.initialize();
            assertTrue(first.commitCreditMutation(new DatabaseManager.CreditMutation(
                    new CreditEntry(creditId, "identity-kept", "alice", "bob", 10_000L, null, null),
                    List.of(), List.of(), List.of())));
            Path sidecar = FileManager.getStorageIdentityFile();
            String originalIdentity = Files.readString(sidecar);
            StorageIdentityGuard guard = new StorageIdentityGuard(new DatabaseMetadataDao(), new RecoveryFileOps());
            String storageUuid = guard.readSidecar(sidecar).orElseThrow().storageUuid();
            Files.writeString(sidecar, originalIdentity.replace(storageUuid, UUID.randomUUID().toString()));

            DatabaseCoordinator mismatched = new DatabaseCoordinator();
            mismatched.initialize();

            assertEquals(DatabaseManager.DatabaseAvailability.STORAGE_IDENTITY_MISMATCH, mismatched.availability());
            assertTrue(mismatched.isWriteLocked());
            Files.writeString(sidecar, originalIdentity);
            DatabaseCoordinator restored = new DatabaseCoordinator();
            restored.initialize();
            assertEquals(creditId, restored.loadCreditState().credits().getFirst().getId());
        }
    }

    @Test
    void explicitFreshRecoveryArchivesEvidenceAndAdvancesStorageGeneration() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator first = new DatabaseCoordinator();
            first.initialize();
            assertTrue(first.commitCreditMutation(new DatabaseManager.CreditMutation(
                    new CreditEntry(UUID.randomUUID(), "quarantined", "alice", "bob", 10_000L, null, null),
                    List.of(), List.of(), List.of())));
            StorageIdentityGuard guard = new StorageIdentityGuard(new DatabaseMetadataDao(), new RecoveryFileOps());
            StorageIdentityGuard.StorageIdentity previous = guard.readSidecar(FileManager.getStorageIdentityFile()).orElseThrow();
            Path active = FileManager.getDatabaseStorageFile();
            byte[] contents = Files.readAllBytes(active);
            Files.write(active, Arrays.copyOf(contents, Math.min(contents.length, 16)));
            DatabaseCoordinator damaged = new DatabaseCoordinator();
            damaged.initialize();

            assertEquals(DatabaseManager.DatabaseAvailability.NEEDS_USER_RECOVERY, damaged.availability());
            assertFalse(Files.exists(active));
            assertTrue(damaged.createEmptyDatabaseAfterPhysicalRecovery());

            StorageIdentityGuard.StorageIdentity replacement = guard.readSidecar(FileManager.getStorageIdentityFile()).orElseThrow();
            assertNotEquals(previous.storageUuid(), replacement.storageUuid());
            assertEquals(previous.generation() + 1L, replacement.generation());
            assertTrue(damaged.loadCreditState().credits().isEmpty());
            try (var identities = Files.list(FileManager.getRecoveryDirectory().resolve("storage-identities"))) {
                assertEquals(1L, identities.filter(Files::isRegularFile).count());
            }
            try (var quarantine = Files.list(FileManager.getQuarantineDirectory())) {
                assertTrue(quarantine.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
            }
        }
    }
}
