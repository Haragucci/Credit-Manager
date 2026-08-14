package op.creditmanager.client.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.config.ClientConfig;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.PaylogAutoLinkMode;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaylogLinkingDatabaseTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;
    private Object previousConfig;
    private boolean previousConfigRecovery;

    @AfterEach
    void restoreStatics() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        configField().set(null, previousConfig);
        configRecoveryField().setBoolean(null, previousConfigRecovery);
    }

    @Test
    void automaticLinkSkipsAnOlderDealThatCannotCoverThePaylog() throws Exception {
        CreditManager manager = manager();
        CreditEntry first = manager.createCredit("creditor", "debtor", 50D, null, "first", null);
        CreditEntry second = manager.createCredit("creditor", "debtor", 100D, null, "second", null);
        first.setCreatedAt(1L);
        second.setCreatedAt(2L);

        TransactionEntry paylog = paylog(60D);
        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId());

        assertTrue(result.linked());
        assertEquals(second.getId(), result.credit().getId());
        assertTrue(manager.getPaymentsForCredit(first.getId()).isEmpty());
        assertEquals(60D, manager.getPaymentsForCredit(second.getId()).getFirst().getAmount());
        assertTrue(op.creditmanager.client.storage.db.DatabaseManager.getInstance().isHealthy());
        assertFalse(op.creditmanager.client.storage.db.DatabaseManager.getInstance().isWriteLocked());
    }

    @Test
    void automaticLinkDoesNotSplitOnePaylogAcrossSeveralDeals() throws Exception {
        CreditManager manager = manager();
        manager.createCredit("creditor", "debtor", 50D, null, "first", null);
        manager.createCredit("creditor", "debtor", 50D, null, "second", null);
        TransactionEntry paylog = paylog(70D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId());

        assertEquals(CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS, result.status());
        assertTrue(manager.getAllCreditsAsCreditor("creditor").stream().allMatch(credit -> credit.getPayments().isEmpty()));
        assertEquals(70D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
        assertTrue(op.creditmanager.client.storage.db.DatabaseManager.getInstance().isHealthy());
        assertFalse(op.creditmanager.client.storage.db.DatabaseManager.getInstance().isWriteLocked());
    }

    @Test
    void paylogDedupeKeepsDifferentRapidEventsButRejectsAnExactDuplicate() throws Exception {
        manager();
        TransactionEntry first = new TransactionEntry("debtor", "creditor", 10D);
        first.setTimestamp(1_000L); first.setRawText("payment event one"); first.setSource("DETECTED");
        TransactionEntry exactDuplicate = new TransactionEntry("debtor", "creditor", 10D);
        exactDuplicate.setTimestamp(1_000L); exactDuplicate.setRawText("payment event one"); exactDuplicate.setSource("DETECTED");
        TransactionEntry distinctRapid = new TransactionEntry("debtor", "creditor", 10D);
        distinctRapid.setTimestamp(1_001L); distinctRapid.setRawText("payment event two"); distinctRapid.setSource("DETECTED");
        assertTrue(TransactionRepository.getInstance().add(first));
        assertFalse(TransactionRepository.getInstance().add(exactDuplicate));
        assertTrue(TransactionRepository.getInstance().add(distinctRapid));
    }

    @Test
    void paylogSearchCombinesNormalizedSourceAmountAndLinkStateTokens() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 2_000D, null, "search", null);
        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", 1_000D);
        paylog.setTimestamp(1_700_000_000_000L);
        paylog.setRawText("manuelle Testzahlung mit Notiz");
        paylog.setSource("MANUAL");
        assertTrue(TransactionRepository.getInstance().add(paylog));
        assertTrue(manager.addPaylogPayment(credit.getId(), paylog.getId(), 500D, paylog.getTimestamp(), null).linked());

        var result = op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryPaylogPage("debtor", 0, "manuell teilweise 1k testzahlung", 500, 0);
        assertEquals(1, result.entries().size());
        assertEquals(paylog.getId(), result.entries().getFirst().getId());
    }

    @Test
    void selectedPaylogPaymentRetainsEditableTimestampAndNote() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "selected", null);
        TransactionEntry paylog = paylog(80D);
        long timestamp = 1_700_000_000_000L;
        CreditManager.PaylogLinkResult result = manager.addPaylogPayment(credit.getId(), paylog.getId(), 40D, timestamp, "vom Nutzer angepasst");
        assertTrue(result.linked());
        assertEquals(40D, result.payment().getAmount());
        assertEquals(timestamp, result.payment().getTimestamp());
        assertEquals("vom Nutzer angepasst", result.payment().getNote());
        assertEquals("PAYLOG_SELECTED", result.payment().getSource());
        CreditRepository reload = new CreditRepository(); reload.load();
        assertEquals("vom Nutzer angepasst", reload.getPaymentsByCreditId(credit.getId()).getFirst().getNote());
        assertEquals(40D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
    }

    @Test
    void manualLinkCapsOverpayAndPaymentDeletionReopensAndReleasesThePaylog() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 50D, null, "single", null);
        TransactionEntry paylog = paylog(80D);

        CreditManager.PaylogLinkResult result = manager.linkPaylogToDeal(paylog.getId(), credit.getId());
        Payment payment = result.payment();
        assertTrue(result.linked());
        assertEquals(50D, payment.getAmount());
        assertEquals(30D, result.remainingPaylogAmount());
        assertEquals(paylog.getId(), payment.getPaylogId());
        assertEquals(CreditManager.STATUS_PAID, credit.getStatus());
        assertEquals(50D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getLinkedAmount());

        CreditRepository reloaded = new CreditRepository();
        reloaded.load();
        Payment persisted = reloaded.getPaymentsByCreditId(credit.getId()).getFirst();
        assertEquals(paylog.getId(), persisted.getPaylogId());

        manager.deletePayment(payment.getId());
        assertEquals(CreditManager.STATUS_OPEN, credit.getStatus());
        assertFalse(credit.isArchived());
        assertEquals(80D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
        assertEquals(0D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getLinkedAmount());

        manager.closeCredit(credit.getId());
        assertFalse(credit.isArchived());
        assertEquals(CreditManager.STATUS_CLOSED, credit.getStatus());
        CreditRepository archivedReload = new CreditRepository();
        archivedReload.load();
        assertFalse(archivedReload.findCreditById(credit.getId()).orElseThrow().isArchived());
        assertEquals(CreditManager.STATUS_CLOSED, archivedReload.findCreditById(credit.getId()).orElseThrow().getStatus());
        manager.reactivateCredit(credit.getId());
        assertFalse(credit.isArchived());
        assertEquals(CreditManager.STATUS_OPEN, credit.getStatus());
    }

    @Test
    void historyHidesArchivesByDefaultKeepsThemLastAndSearchesNormalizedFields() throws Exception {
        CreditManager manager = manager();
        CreditEntry visible = manager.createCredit("creditor", "spieler_name", 1_000D, null, "visible", "sichtbare Notiz");
        manager.addMoneyPayment(visible.getId(), "spieler_name", 1_000D);
        CreditEntry closed = manager.createCredit("creditor", "other_player", 50D, null, "closed", null);
        manager.closeCredit(closed.getId());
        CreditEntry archived = manager.createCredit("creditor", "archived_player", 20D, null, "archive", null);
        manager.addMoneyPayment(archived.getId(), "archived_player", 20D);
        manager.archiveCredit(archived.getId());
        assertEquals(CreditManager.STATUS_PAID, archived.getStatus());

        var defaultHistory = op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", "spieler name", false, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.NEWEST, 500, 0);
        assertEquals(1, defaultHistory.entries().size());
        assertEquals(visible.getId(), defaultHistory.entries().getFirst().getId());

        var allHistory = op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", "", true, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.AMOUNT_ASC, 500, 0);
        assertTrue(allHistory.entries().getLast().isArchived());
        assertEquals(1, op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", "abgeschlossen", false, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.NEWEST, 500, 0).entries().size());
        assertEquals(1, op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", "1k", false, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.NEWEST, 500, 0).entries().size());
        assertEquals(1, op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", "1000", false, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.NEWEST, 500, 0).entries().size());
        assertEquals(1, op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", "1.000,00", false, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.NEWEST, 500, 0).entries().size());
        assertEquals(1, op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", visible.getId().toString().substring(0, 8), false, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.NEWEST, 500, 0).entries().size());
        assertEquals(1, op.creditmanager.client.storage.db.DatabaseManager.getInstance()
                .queryDealHistoryPage("creditor", "archiviert bezahlt", true, op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort.NEWEST, 500, 0).entries().size());
    }

    @Test
    void coreRejectsInvalidNamesAmountsLabelsAndDueDates() throws Exception {
        CreditManager manager = manager();
        assertThrows(CreditManager.CreditException.class,
                () -> manager.createCredit("creditor!", "debtor", 1D, null, null, null));
        assertThrows(CreditManager.CreditException.class,
                () -> manager.createCredit("creditor", "debtor", Double.POSITIVE_INFINITY, null, null, null));
        assertThrows(CreditManager.CreditException.class,
                () -> manager.createCredit("creditor", "debtor", 1D, null, "x".repeat(129), null));
        assertThrows(CreditManager.CreditException.class,
                () -> manager.createCredit("creditor", "debtor", 1D, System.currentTimeMillis() + 3_155_846_400_000L, null, null));
    }

    @Test
    void legacyManualPaymentSourceMustMatchTheDebtor() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 10D, null, "payment_source", null);

        assertThrows(CreditManager.CreditException.class, () -> manager.addMoneyPayment(credit.getId(), "creditor", 1D));
        assertEquals(1D, manager.addMoneyPayment(credit.getId(), 1D).getAmount());
    }

    @Test
    void healthCheckFindsBrokenPaylogLinks() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "health", null);
        TransactionEntry paylog = paylog(10D);
        String jdbc = "jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE";
        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            statement.executeUpdate("INSERT INTO payments (id, credit_id, from_player, to_player, amount, payment_kind, items_json, item_nbt_entries, created_at, source, paylog_id, revision) VALUES ('" + UUID.randomUUID() + "', '" + credit.getId() + "', 'wrong', 'direction', 2000, 'MONEY', '[]', '[]', 1, 'MANUELL', '" + paylog.getId() + "', 0)");
            statement.executeUpdate("INSERT INTO payments (id, credit_id, from_player, to_player, amount, payment_kind, items_json, item_nbt_entries, created_at, source, paylog_id, revision) VALUES ('" + UUID.randomUUID() + "', '" + credit.getId() + "', 'debtor', 'creditor', 100, 'MONEY', '[]', '[]', 1, 'PAYLOG_SELECTED', '" + UUID.randomUUID() + "', 0)");
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        var findings = op.creditmanager.client.storage.db.DatabaseManager.getInstance().runHealthCheck();
        assertTrue(findings.stream().anyMatch(record -> "PAYLOG_LINK_DIRECTION".equals(record.type())));
        assertTrue(findings.stream().anyMatch(record -> "PAYLOG_LINK_SOURCE".equals(record.type())));
        assertTrue(findings.stream().anyMatch(record -> "PAYLOG_LINK_AMOUNT".equals(record.type())));
        assertTrue(findings.stream().anyMatch(record -> "PAYLOG_LINK_ORPHAN".equals(record.type())));
        assertTrue(findings.stream().anyMatch(record -> "PAYLOG_LINK_OVERBOOKED".equals(record.type())));
    }

    @Test
    void autoLinkExactOnlyRejectsPartialPayment() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "exact", null);
        TransactionEntry paylog = paylog(50D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.EXACT_ONLY, false);

        assertEquals(CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS, result.status());
        assertTrue(manager.getPaymentsForCredit(credit.getId()).isEmpty());
        assertEquals(50D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
    }

    @Test
    void autoLinkOffKeepsMatchingPaylogUnlinked() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "off", null);
        TransactionEntry paylog = paylog(100D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.OFF, false);

        assertEquals(CreditManager.PaylogLinkResult.Status.AUTO_LINK_DISABLED, result.status());
        assertEquals(credit.getId(), result.credit().getId());
        assertTrue(manager.getPaymentsForCredit(credit.getId()).isEmpty());
        assertEquals(100D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
    }

    @Test
    void autoLinkExactOnlyRejectsOverpay() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "exact", null);
        TransactionEntry paylog = paylog(120D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.EXACT_ONLY, false);

        assertEquals(CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS, result.status());
        assertTrue(manager.getPaymentsForCredit(credit.getId()).isEmpty());
        assertEquals(120D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
    }

    @Test
    void autoLinkExactOnlyLinksExactPayment() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "exact", null);
        TransactionEntry paylog = paylog(100D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.EXACT_ONLY, false);

        assertTrue(result.linked());
        assertEquals(credit.getId(), result.credit().getId());
        assertEquals(CreditManager.STATUS_PAID, credit.getStatus());
        assertEquals(0D, result.remainingPaylogAmount());
    }

    @Test
    void autoLinkPartialModeLinksPartialPayment() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "partial", null);
        TransactionEntry paylog = paylog(50D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.EXACT_OR_PARTIAL, false);

        assertTrue(result.linked());
        assertEquals(50D, result.payment().getAmount());
        assertEquals(CreditManager.STATUS_PARTIAL, credit.getStatus());
        assertEquals(0D, result.remainingPaylogAmount());
    }

    @Test
    void autoLinkPartialModeRejectsOverpay() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "partial", null);
        TransactionEntry paylog = paylog(120D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.EXACT_OR_PARTIAL, false);

        assertEquals(CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS, result.status());
        assertTrue(manager.getPaymentsForCredit(credit.getId()).isEmpty());
        assertEquals(120D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
    }

    @Test
    void autoLinkOverpayModeClosesDealAndKeepsPaylogRemainder() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "overpay", null);
        TransactionEntry paylog = paylog(120D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(),
                PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE, true);

        assertTrue(result.linked());
        assertEquals(100D, result.payment().getAmount());
        assertEquals(20D, result.remainingPaylogAmount());
        assertEquals(CreditManager.STATUS_PAID, credit.getStatus());
        TransactionEntry linkedPaylog = TransactionRepository.getInstance().find(paylog.getId()).orElseThrow();
        assertEquals(100D, linkedPaylog.getLinkedAmount());
        assertEquals(20D, linkedPaylog.getRemainingAmount());
    }

    @Test
    void autoLinkOverpayModeRequiresCompletionSetting() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "overpay_setting", null);
        TransactionEntry paylog = paylog(120D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(),
                PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE, false);

        assertEquals(CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS, result.status());
        assertTrue(manager.getPaymentsForCredit(credit.getId()).isEmpty());
        assertEquals(120D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
    }

    @Test
    void autoLinkPrefersExactMatchOverOlderPartialCandidate() throws Exception {
        CreditManager manager = manager();
        CreditEntry olderPartial = manager.createCredit("creditor", "debtor", 150D, null, "older", null);
        CreditEntry exact = manager.createCredit("creditor", "debtor", 100D, null, "exact", null);
        olderPartial.setCreatedAt(1L);
        exact.setCreatedAt(2L);
        TransactionEntry paylog = paylog(100D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.EXACT_OR_PARTIAL, false);

        assertTrue(result.linked());
        assertEquals(exact.getId(), result.credit().getId());
        assertTrue(manager.getPaymentsForCredit(olderPartial.getId()).isEmpty());
    }

    @Test
    void autoLinkMultipleCandidatesDoesNotPickRandomly() throws Exception {
        CreditManager manager = manager();
        CreditEntry oldest = manager.createCredit("creditor", "debtor", 100D, null, "oldest", null);
        CreditEntry newer = manager.createCredit("creditor", "debtor", 100D, null, "newer", null);
        manager.getSafeCredit(oldest.getId()).setCreatedAt(1L);
        manager.getSafeCredit(newer.getId()).setCreatedAt(2L);
        TransactionEntry paylog = paylog(50D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId(), PaylogAutoLinkMode.EXACT_OR_PARTIAL, false);

        assertEquals(CreditManager.PaylogLinkResult.Status.AMBIGUOUS, result.status());
        assertEquals(java.util.Set.of(oldest.getId(), newer.getId()), java.util.Set.copyOf(result.candidateIds()));
        assertTrue(manager.getPaymentsForCredit(oldest.getId()).isEmpty());
        assertTrue(manager.getPaymentsForCredit(newer.getId()).isEmpty());
    }

    private CreditManager manager() throws Exception {
        useTemporaryDataDirectory();
        CreditRepository repository = new CreditRepository();
        repository.load();
        CreditEventRepository.getInstance().load();
        TransactionRepository.getInstance().load();
        return new CreditManager(repository);
    }

    private TransactionEntry paylog(double amount) {
        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", amount);
        paylog.setRawText("manual test paylog " + UUID.randomUUID());
        paylog.setSource("TEST");
        assertTrue(TransactionRepository.getInstance().add(paylog));
        assertNotNull(paylog.getId());
        return paylog;
    }

    private void useTemporaryDataDirectory() throws Exception {
        Files.createDirectories(dataDirectory);
        previousDirectory = (Path) dataDirectoryField().get(null);
        previousConfig = configField().get(null);
        previousConfigRecovery = configRecoveryField().getBoolean(null);
        dataDirectoryField().set(null, dataDirectory);
        configField().set(null, new ClientConfig());
        configRecoveryField().setBoolean(null, false);
    }

    private Field dataDirectoryField() throws Exception { Field field = FileManager.class.getDeclaredField("dataDirectory"); field.setAccessible(true); return field; }
    private Field configField() throws Exception { Field field = ClientConfigManager.class.getDeclaredField("config"); field.setAccessible(true); return field; }
    private Field configRecoveryField() throws Exception { Field field = ClientConfigManager.class.getDeclaredField("recoveryRequired"); field.setAccessible(true); return field; }
}
