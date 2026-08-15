package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.FileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaylogPersistenceDatabaseTest {

    @TempDir
    Path temporary;

    @Test
    void paylogPaginationDedupeAndBackupAreDatabaseBacked() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);

            DatabaseCoordinator database = new DatabaseCoordinator();
            database.initialize();

            List<TransactionEntry> entries = new ArrayList<>(1_001);

            for (int index = 0; index < 1_001; index++) {
                TransactionEntry entry = new TransactionEntry(
                        "payer" + index,
                        "receiver",
                        (index + 1L) * 100L
                );

                entry.setTimestamp(1_000_000L + index * 3_000L);
                entry.setRawText("TEST_PAYLOG_" + index);
                entry.setSource("TEST");

                entries.add(entry);
            }

            DatabaseManager.BatchInsertResult insertResult =
                    database.addPaylogsBatchDetailed(entries);

            assertEquals(1_001, insertResult.requested());
            assertEquals(1_001, insertResult.inserted());
            assertEquals(0, insertResult.skipped());
            assertEquals(0, insertResult.failed());

            assertEquals(
                    1_001,
                    database.findPaylogCandidates(0L, Long.MAX_VALUE).size()
            );

            DatabaseManager.QueryPage<TransactionEntry> first =
                    database.queryPaylogPage(
                            "receiver",
                            0,
                            "",
                            DatabaseManager.PAGE_SIZE,
                            0
                    );

            DatabaseManager.QueryPage<TransactionEntry> third =
                    database.queryPaylogPage(
                            "receiver",
                            0,
                            "",
                            DatabaseManager.PAGE_SIZE,
                            1_000
                    );

            assertEquals(1_001L, first.totalCount());
            assertEquals(DatabaseManager.PAGE_SIZE, first.entries().size());
            assertTrue(first.hasNext());

            assertEquals(1_001L, third.totalCount());
            assertEquals(1, third.entries().size());
            assertFalse(third.hasNext());

            TransactionEntry duplicate = new TransactionEntry(
                    "same",
                    "receiver",
                    500L
            );
            duplicate.setTimestamp(9_000_000L);
            duplicate.setRawText("TEST_DUPLICATE");
            duplicate.setSource("TEST");

            assertTrue(database.addPaylog(duplicate));

            TransactionEntry repeated = new TransactionEntry(
                    "same",
                    "receiver",
                    500L
            );
            repeated.setTimestamp(9_000_000L);
            repeated.setRawText("TEST_DUPLICATE");
            repeated.setSource("TEST");

            assertFalse(database.addPaylog(repeated));

            TransactionEntry distinctRapid = new TransactionEntry(
                    "same",
                    "receiver",
                    500L
            );
            distinctRapid.setTimestamp(9_000_001L);
            distinctRapid.setRawText("TEST_DUPLICATE");
            distinctRapid.setSource("TEST");

            assertTrue(database.addPaylog(distinctRapid));

            assertTrue(database.createBackup());

            try (var files = Files.list(
                    FileManager.getDataDirectory().resolve("backups")
            )) {
                assertTrue(
                        files.anyMatch(path ->
                                path.getFileName()
                                        .toString()
                                        .matches("creditmanager_backup_.*\\.zip")
                        )
                );
            }

            assertFalse(database.listBackups().isEmpty());
        }
    }
}