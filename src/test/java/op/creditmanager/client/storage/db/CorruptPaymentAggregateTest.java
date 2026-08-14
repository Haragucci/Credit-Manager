package op.creditmanager.client.storage.db;

import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.FileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorruptPaymentAggregateTest {
    @TempDir Path temporary;

    @Test
    void overflowingPersistedPaymentSumBecomesDataHealthInsteadOfRuntimeException() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            UUID creditId = UUID.randomUUID();
            CreditEntry credit = new CreditEntry(creditId, "overflow", "alice", "bob", MoneyRules.MAX_MINOR, null, null);
            assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));

            String url = "jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE";
            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement("INSERT INTO payments (id,credit_id,from_player,to_player,amount,payment_kind,items_json,item_nbt_entries,created_at,source,revision) VALUES (?,?,?,?,?,'MONEY','[]','[]',?,'CORRUPT_FIXTURE',1)")) {
                for (int index = 0; index < 93; index++) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, creditId.toString());
                    statement.setString(3, "bob");
                    statement.setString(4, "alice");
                    statement.setLong(5, MoneyRules.MAX_MINOR);
                    statement.setLong(6, index + 1L);
                    statement.addBatch();
                }
                statement.executeBatch();
            }

            DatabaseManager.DatabaseState state = assertDoesNotThrow(coordinator::loadRuntimeCreditState);
            assertEquals(1, state.credits().size());
            assertEquals(93, state.payments().size());
            assertEquals(0L, state.credits().getFirst().getPaidAmountMinor());
            assertFalse(coordinator.isSafeForWrites());
            assertTrue(coordinator.listHealthRecords(false).stream()
                    .anyMatch(record -> "CREDIT_PAYMENT_TOTAL".equals(record.type()) && "ERROR".equals(record.severity())));
        }
    }
}
