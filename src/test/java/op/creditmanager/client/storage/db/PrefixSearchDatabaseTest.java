package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixSearchDatabaseTest {
    @TempDir Path temporary;

    @Test
    void paylogAndAvailablePaymentSearchMatchEveryLeadingNameFragment() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator database = new DatabaseCoordinator();
            database.initialize();
            TransactionEntry paylog = new TransactionEntry("gerry237", "alice", 10_000L);
            paylog.setTimestamp(1_700_000_000_000L);
            paylog.setRawText("Premium Handel");
            paylog.setSource("DETECTED");
            assertTrue(database.addPaylog(paylog));

            for (String query : List.of("ge", "ger", "gerr", "gerry237")) {
                assertEquals(paylog.getId(), database.queryPaylogPage("alice", 0, query, 500, 0)
                        .entries().getFirst().getId());
            }
            assertEquals(paylog.getId(), database.queryAvailablePaylogs("gerry237", "alice", "prem han", 500, 0)
                    .entries().getFirst().getId());
            assertTrue(database.queryPaylogPage("alice", 0, "erry", 500, 0).entries().isEmpty());
        }
    }

    @Test
    void historySearchCombinesPrefixesFromDifferentIndexedFields() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseCoordinator database = new DatabaseCoordinator();
            database.initialize();
            CreditEntry credit = new CreditEntry(UUID.randomUUID(), "gerry237-handel", "alice", "gerry237",
                    10_000L, null, "Premium Verkauf");
            Payment payment = new Payment(credit.getId(), "gerry237", "alice", 10_000L, null, "MANUELL");
            credit.addPayment(payment);
            assertTrue(database.commitCreditMutationWithReceipt(new DatabaseManager.CreditMutation(
                    credit, List.of(payment), List.of(), List.of())).verified());

            for (String query : List.of("ge", "ger", "gerr", "gerry237", "gerr han", "prem verk")) {
                assertEquals(credit.getId(), database.queryDealHistoryPage("alice", query, 500, 0)
                        .entries().getFirst().getId());
            }
            assertTrue(database.queryDealHistoryPage("alice", "erry", 500, 0).entries().isEmpty());
        }
    }
}
