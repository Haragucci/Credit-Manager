package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.ProcessStorageLease;
import op.creditmanager.client.storage.StorageRootResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiInstanceDatabaseSafetyTest {
    @TempDir Path temporary;

    @Test
    void secondaryInstanceOpensNoDatabaseAndCanTakeOverAfterPrimaryCloses() throws Exception {
        Path root = temporary.resolve("CreditManagerLogs");
        StorageRootResolver.StorageLocation location = new StorageRootResolver.StorageLocation(root, null,
                StorageRootResolver.StorageEnvironment.STANDARD, "shared-instance",
                StorageRootResolver.ResolutionSource.STANDARD_GAME_DIR, true, List.of("test"));
        ProcessStorageLease primary = ProcessStorageLease.tryAcquire(root).orElseThrow();
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configure(root, location, FileManager.StorageAccessState.SECONDARY_INSTANCE);
            DatabaseCoordinator secondary = new DatabaseCoordinator();

            secondary.initialize();

            assertEquals(DatabaseManager.DatabaseAvailability.SECONDARY_INSTANCE, secondary.availability());
            assertTrue(secondary.isWriteLocked());
            assertFalse(Files.exists(root.resolve("creditmanager.mv.db")));
            assertFalse(secondary.createBackup());
            assertFalse(Files.exists(root.resolve("creditmanager.mv.db")));

            primary.close();
            primary = null;

            assertTrue(secondary.recheckAndRepair());
            assertTrue(secondary.isHealthy());
            assertTrue(Files.isRegularFile(root.resolve("creditmanager.mv.db")));
            assertTrue(FileManager.hasPrimaryStorageLease());
        } finally {
            if (primary != null) primary.close();
        }
    }
}
