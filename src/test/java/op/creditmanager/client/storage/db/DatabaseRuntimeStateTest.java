package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRuntimeStateTest {
    @TempDir Path temporary;

    @Test
    void unknownStateIsFailClosedWithoutOpeningTheDatabase() {
        DatabaseCoordinator coordinator = new DatabaseCoordinator();

        assertEquals(DatabaseManager.DatabaseAvailability.UNKNOWN, coordinator.availability());
        assertFalse(coordinator.isHealthy());
        assertTrue(coordinator.isWriteLocked());
        assertFalse(coordinator.isSafeForWrites());
        assertEquals(0L, coordinator.revision());
        assertEquals(0L, coordinator.openedConnectionCount());
    }

    @Test
    void runtimeGettersAndRevisionUseNoConnectionsAfterInitialization() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            long opened = coordinator.openedConnectionCount();

            for (int index = 0; index < 10_000; index++) {
                assertEquals(DatabaseManager.DatabaseAvailability.HEALTHY, coordinator.availability());
                assertFalse(coordinator.requiresUserRecovery());
                assertTrue(coordinator.isHealthy());
                assertFalse(coordinator.isWriteLocked());
                assertTrue(coordinator.isSafeForWrites());
                assertEquals(0L, coordinator.revision());
            }

            assertEquals(opened, coordinator.openedConnectionCount());
        }
    }

    @Test
    void authoritativeWriteGateInvalidatesAStaleSafeSnapshotAndResolveReopensWrites() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            UUID findingId = UUID.randomUUID();
            try (Connection connection = coordinator.connection(); PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO data_health_records (id,record_type,severity,title,message,status,created_at) VALUES (?,?,?,?,?,'OPEN',?)")) {
                statement.setString(1, findingId.toString());
                statement.setString(2, "TEST_OPEN");
                statement.setString(3, "ERROR");
                statement.setString(4, "test");
                statement.setString(5, "test");
                statement.setLong(6, 1L);
                statement.executeUpdate();
            }
            assertTrue(coordinator.isSafeForWrites());

            CreditEntry blocked = new CreditEntry(UUID.randomUUID(), "blocked", "alice", "bob", 1_000L, null, null);
            assertFalse(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(blocked, List.of(), List.of(), List.of())));
            assertFalse(coordinator.isSafeForWrites());

            assertTrue(coordinator.resolveHealthRecord(findingId, "resolved", false));
            assertTrue(coordinator.isSafeForWrites());
            coordinator.updateBackupProtection(BackupCheckpointService.ProtectionState.CRITICAL);
            assertFalse(coordinator.isSafeForWrites());
            coordinator.updateBackupProtection(BackupCheckpointService.ProtectionState.LOCAL_ONLY);
            assertTrue(coordinator.isSafeForWrites());
        }
    }
}
