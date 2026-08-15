package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.TransactionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseReadRecoveryLockTest {
    @TempDir Path temporary;

    @Test
    void schemaRepairDoesNotUpgradeTheLifecycleReadLock() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            TransactionEntry paylog = new TransactionEntry("alice", "bob", 1_000L);
            paylog.setRawText("needle");
            paylog.setNormalizedText("needle");
            paylog.setTimestamp(1L);
            paylog.setSource("TEST");
            assertEquals(1, coordinator.addPaylogsBatch(List.of(paylog)));
            try (Connection connection = coordinator.connection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE paylog_search_tokens");
            }

            FutureTask<DatabaseManager.QueryPage<TransactionEntry>> task = new FutureTask<>(
                    () -> coordinator.queryPaylogPage("", 0, "needle", 10, 0));
            Thread thread = new Thread(task, "creditmanager-schema-repair-test");
            thread.setDaemon(true);
            thread.start();

            DatabaseManager.QueryPage<TransactionEntry> page = task.get(10, TimeUnit.SECONDS);
            assertEquals(1, page.entries().size());
            assertEquals("needle", page.entries().getFirst().getRawText());
        }
    }
}
