package op.creditmanager.client.storage.db;

import op.creditmanager.client.core.CreditEventRepository;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.service.MutationCommitResult;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClaimDebtPaymentPersistenceTest {
    @TempDir Path temporary;

    @Test
    void realIncidentPartialMoneyValuesPersistForClaimsAndDebtsAcrossRestart() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            CreditManager manager = manager();
            long[][] samples = {{10_000L, 1_000L}, {15_000L, 5_000L}, {212_121L, 21L}, {212_121L, 2_123L}};
            List<UUID> ids = new ArrayList<>();
            int index = 0;
            for (long[] sample : samples) {
                CreditEntry claim = manager.createCreditMinor("alice", "bob", sample[0], null, "claim-" + index, null);
                CreditEntry debt = manager.createCreditMinor("bob", "alice", sample[0], null, "debt-" + index, null);
                manager.addMoneyPaymentMinor(claim.getId(), sample[1]);
                manager.addMoneyPaymentMinor(debt.getId(), sample[1]);
                assertEquals(CreditManager.STATUS_PARTIAL, claim.getStatus());
                assertEquals(CreditManager.STATUS_PARTIAL, debt.getStatus());
                assertEquals(sample[1], claim.getPaidAmountMinor());
                assertEquals(sample[1], debt.getPaidAmountMinor());
                ids.add(claim.getId());
                ids.add(debt.getId());
                index++;
            }
            long revision = DatabaseManager.getInstance().revision();

            CreditRepository reloaded = reloadRepository();

            assertEquals(revision, DatabaseManager.getInstance().revision());
            for (int sampleIndex = 0; sampleIndex < samples.length; sampleIndex++) {
                for (int side = 0; side < 2; side++) {
                    CreditEntry entry = reloaded.findCreditById(ids.get(sampleIndex * 2 + side)).orElseThrow();
                    assertEquals(CreditManager.STATUS_PARTIAL, entry.getStatus());
                    assertEquals(samples[sampleIndex][1], entry.getPaidAmountMinor());
                    assertEquals(1, entry.getPayments().size());
                }
            }
            assertTrue(FilesSupport.databaseExists(temporary));
        }
    }

    @Test
    void itemAndCombinedPaymentsPersistAndPaidDealNameRemainsDiscoverable() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            CreditManager manager = manager();
            CreditEntry claim = manager.createCreditMinor("alice", "bob", 20_000L, null, "item-claim", null);
            CreditEntry debt = manager.createCreditMinor("bob", "alice", 20_000L, null, "item-debt", null);
            Payment claimPayment = manager.addItemPaymentMinor(claim.getId(), List.of("diamond x1"), 2_000L, "{}");
            assertEquals(0L, debt.getPaidAmountMinor());
            long beforeDebtPayment = DatabaseManager.getInstance().revision();
            Payment debtPayment = manager.addItemPaymentMinor(debt.getId(), List.of("emerald x2"), 3_000L, "{}");
            MutationCommitResult debtCommit = manager.consumeLastMutationCommit();
            assertNotEquals(claimPayment.getId(), debtPayment.getId());
            assertEquals(claim.getId(), claimPayment.getCreditId());
            assertEquals(debt.getId(), debtPayment.getCreditId());
            assertTrue(debtCommit.committed());
            assertEquals(beforeDebtPayment + 1L, debtCommit.committedRevision());
            assertEquals(beforeDebtPayment + 1L, DatabaseManager.getInstance().revision());
            assertEquals(2_000L, persisted(claim.getId()).getPaidAmountMinor());
            assertEquals(3_000L, persisted(debt.getId()).getPaidAmountMinor());
            CreditEntry combined = manager.createCreditMinor("alice", "charlie", 10_000L, null, "combined", null);
            manager.addMoneyPaymentMinor(combined.getId(), 1_000L);
            manager.addItemPaymentMinor(combined.getId(), List.of("gold_ingot x4"), 2_000L, "{}");
            manager.addMoneyPaymentMinor(combined.getId(), 7_000L);

            assertEquals(CreditManager.STATUS_PARTIAL, claim.getStatus());
            assertEquals(CreditManager.STATUS_PARTIAL, debt.getStatus());
            assertEquals(CreditManager.STATUS_PAID, combined.getStatus());
            assertTrue(manager.getDealNamesForPlayer("alice").contains(combined.getDealName()));
            assertThrows(CreditManager.CreditException.class,
                    () -> manager.createCreditMinor("alice", "charlie", 10_000L, null, "combined", null));

            CreditRepository reloaded = reloadRepository();
            CreditEntry reloadedClaim = reloaded.findCreditById(claim.getId()).orElseThrow();
            CreditEntry reloadedDebt = reloaded.findCreditById(debt.getId()).orElseThrow();
            CreditEntry reloadedCombined = reloaded.findCreditById(combined.getId()).orElseThrow();
            assertEquals(2_000L, reloadedClaim.getPaidAmountMinor());
            assertEquals(3_000L, reloadedDebt.getPaidAmountMinor());
            assertEquals(10_000L, reloadedCombined.getPaidAmountMinor());
            assertEquals(CreditManager.STATUS_PAID, reloadedCombined.getStatus());
            assertEquals(3, reloadedCombined.getPayments().size());
            assertEquals(10_000L, reloadedCombined.getPayments().stream().mapToLong(Payment::getAmountMinor).sum());
        }
    }

    private CreditManager manager() {
        DatabaseManager.getInstance().initialize();
        CreditRepository repository = reloadRepository();
        return new CreditManager(repository);
    }

    private CreditRepository reloadRepository() {
        CreditRepository repository = new CreditRepository();
        repository.load();
        CreditEventRepository.getInstance().load();
        return repository;
    }

    private CreditEntry persisted(UUID id) {
        return DatabaseManager.getInstance().loadCreditState().credits().stream()
                .filter(entry -> id.equals(entry.getId())).findFirst().orElseThrow();
    }

    private static final class FilesSupport {
        private static boolean databaseExists(Path root) {
            return java.nio.file.Files.isRegularFile(root.resolve("creditmanager.mv.db"));
        }
    }
}
