package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.StorageRootResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyOverlayStorageMigrationTest {
    @TempDir Path temporary;

    @Test
    void legacyOverlayDatabaseIsBackedUpValidatedAndInstalledWithoutDeletingSource() throws Exception {
        Path instance = temporary.resolve("LabyMod").resolve("instances").resolve("opsucht");
        Path legacy = instance.resolve("overlay").resolve("CreditManagerLogs");
        Path canonical = instance.resolve("CreditManagerLogs");
        UUID creditId = UUID.randomUUID();
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(legacy);
            DatabaseCoordinator source = new DatabaseCoordinator();
            source.initialize();
            CreditEntry entry = new CreditEntry(creditId, "legacy-debt", "creditor", "debtor", 15_000L, null, "kept");
            assertTrue(source.commitCreditMutation(new DatabaseManager.CreditMutation(entry, List.of(), List.of(), List.of())));
            Path legacyDatabase = FileManager.getDatabaseStorageFile();
            String sourceHash = sha256(legacyDatabase);

            scope.configure(canonical, labyLocation(canonical, legacy), FileManager.StorageAccessState.EXTERNALLY_MANAGED);
            DatabaseCoordinator upgraded = new DatabaseCoordinator();
            upgraded.initialize();

            assertTrue(upgraded.isHealthy());
            assertEquals(creditId, upgraded.loadCreditState().credits().getFirst().getId());
            assertTrue(Files.isRegularFile(legacyDatabase));
            assertEquals(sourceHash, sha256(legacyDatabase));
            assertTrue(Files.isRegularFile(canonical.resolve("creditmanager.mv.db")));
            assertTrue(Files.isRegularFile(canonical.resolve("storage_identity.json")));

            deleteTree(instance.resolve("overlay"));

            DatabaseCoordinator restarted = new DatabaseCoordinator();
            restarted.initialize();
            assertTrue(restarted.isHealthy());
            assertEquals(creditId, restarted.loadCreditState().credits().getFirst().getId());
        }
    }

    @Test
    void differingLegacyAndCanonicalDatabasesBecomeAConflictWithoutOverwrite() throws Exception {
        Path instance = temporary.resolve("LabyMod").resolve("instances").resolve("conflict");
        Path legacy = instance.resolve("overlay").resolve("CreditManagerLogs");
        Path canonical = instance.resolve("CreditManagerLogs");
        try (StorageTestScope scope = new StorageTestScope()) {
            createDatabase(scope, legacy, "legacy", 10_000L);
            createDatabase(scope, canonical, "canonical", 20_000L);
            Path legacyDatabase = legacy.resolve("creditmanager.mv.db");
            Path canonicalDatabase = canonical.resolve("creditmanager.mv.db");
            String legacyHash = sha256(legacyDatabase);
            String canonicalHash = sha256(canonicalDatabase);

            scope.configure(canonical, labyLocation(canonical, legacy), FileManager.StorageAccessState.EXTERNALLY_MANAGED);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();

            assertEquals(DatabaseManager.DatabaseAvailability.STORAGE_CONFLICT, coordinator.availability());
            assertTrue(coordinator.isWriteLocked());
            assertEquals(legacyHash, sha256(legacyDatabase));
            assertEquals(canonicalHash, sha256(canonicalDatabase));
        }
    }

    private void createDatabase(StorageTestScope scope, Path root, String label, long amount) throws Exception {
        scope.configureExternal(root);
        DatabaseCoordinator coordinator = new DatabaseCoordinator();
        coordinator.initialize();
        CreditEntry entry = new CreditEntry(UUID.randomUUID(), label, "creditor", "debtor", amount, null, null);
        assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(entry, List.of(), List.of(), List.of())));
    }

    private StorageRootResolver.StorageLocation labyLocation(Path canonical, Path legacy) {
        return new StorageRootResolver.StorageLocation(canonical.toAbsolutePath().normalize(), legacy.toAbsolutePath().normalize(),
                StorageRootResolver.StorageEnvironment.LABYMOD, "opsucht",
                StorageRootResolver.ResolutionSource.CROSS_VALIDATED, true, List.of("test"));
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
