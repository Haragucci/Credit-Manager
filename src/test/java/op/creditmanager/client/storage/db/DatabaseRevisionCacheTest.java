package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRevisionCacheTest {
    @TempDir Path temporary;

    @Test
    void successfulAndFailedTransactionsPublishOnlyCommittedRevisions() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            CreditEntry first = credit(new UUID(71L, 1L), "first");

            assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(first, List.of(), List.of(), List.of())));
            assertEquals(1L, coordinator.revision());
            long opened = coordinator.openedConnectionCount();
            for (int index = 0; index < 10_000; index++) assertEquals(1L, coordinator.revision());
            assertEquals(opened, coordinator.openedConnectionCount());

            DatabaseCoordinator failing = new DatabaseCoordinator(point -> {
                if (point == DatabaseFaultInjector.FailurePoint.AFTER_CREDIT_UPSERT) throw new IllegalStateException("injected");
            });
            CreditEntry rolledBack = credit(new UUID(71L, 2L), "rolled-back");
            assertFalse(failing.commitCreditMutation(new DatabaseManager.CreditMutation(rolledBack, List.of(), List.of(), List.of())));
            assertEquals(1L, failing.revision());

            assertTrue(coordinator.commitCreditMutationsBatch(List.of(
                    new DatabaseManager.CreditMutation(credit(new UUID(71L, 3L), "batch-a"), List.of(), List.of(), List.of()),
                    new DatabaseManager.CreditMutation(credit(new UUID(71L, 4L), "batch-b"), List.of(), List.of(), List.of()))));
            assertEquals(2L, coordinator.revision());
        }
    }

    @Test
    void restoreReinitializesTheRuntimeRevisionFromTheInstalledBackup() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(
                    credit(new UUID(72L, 1L), "protected"), List.of(), List.of(), List.of())));
            assertTrue(coordinator.createBackup());
            assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(
                    credit(new UUID(72L, 2L), "newer"), List.of(), List.of(), List.of())));
            assertEquals(2L, coordinator.revision());

            assertTrue(coordinator.restoreLatestValidBackup());

            assertEquals(1L, coordinator.revision());
            assertEquals(List.of("protected"), coordinator.loadRuntimeCreditState().credits().stream()
                    .map(CreditEntry::getNote).toList());
        }
    }

    private CreditEntry credit(UUID id, String note) {
        return new CreditEntry(id, "revision", "alice", "bob" + id.getLeastSignificantBits(), 10_000L, null, note);
    }
}
