package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseFailureInjectionTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;
    private DatabaseCoordinator database;

    @BeforeEach
    void initializeDatabase() throws Exception {
        Field field = dataDirectoryField();
        previousDirectory = (Path) field.get(null);
        field.set(null, dataDirectory);
        FileManager.initialize();
        database = new DatabaseCoordinator();
        database.initialize();
    }

    @AfterEach
    void restoreDataDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        DataHealth.clearReasons();
    }

    @Test
    void paymentAndEventFailureRollsBackTheCompleteMutation() throws Exception {
        CreditEntry credit = credit(new UUID(10L, 1L));
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
        long revision = database.revision();
        Payment payment = new Payment(credit.getId(), "debtor", "creditor", 2_500L, List.of(), "MANUAL");
        credit.setPaidAmountMinor(2_500L);
        credit.setStatus("PARTIAL");
        CreditEventEntry event = new CreditEventEntry(CreditEventType.PAYMENT_ADDED, credit, 2_500L, 0L, null, "creditor", "MANUAL", false);
        DatabaseCoordinator failing = coordinatorFailingAt(DatabaseFaultInjector.FailurePoint.AFTER_PAYMENT_UPSERT_BEFORE_EVENT);

        assertFalse(failing.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(payment), List.of(), List.of(event))));

        assertEquals(revision, database.revision());
        assertEquals(0L, scalar("SELECT COUNT(*) FROM payments"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM credit_events"));
        assertEquals(0L, scalar("SELECT paid_amount FROM credits WHERE id='" + credit.getId() + "'"));
    }

    @Test
    void aggregateRefreshFailureRollsBackPaymentCreditAndPaylog() throws Exception {
        CreditEntry credit = credit(new UUID(11L, 1L));
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", 5_000L);
        paylog.setRawText("failure injection paylog");
        paylog.setSource("DETECTED");
        assertTrue(database.addPaylog(paylog));
        long revision = database.revision();
        Payment payment = new Payment(credit.getId(), "debtor", "creditor", 2_500L, List.of(), "PAYLOG_MANUAL");
        payment.setPaylogId(paylog.getId());
        credit.setPaidAmountMinor(2_500L);
        credit.setStatus("PARTIAL");
        DatabaseCoordinator failing = coordinatorFailingAt(DatabaseFaultInjector.FailurePoint.AFTER_PAYLOG_AGGREGATE_REFRESH_BEFORE_COMMIT);

        assertFalse(failing.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(payment), List.of(), List.of())));

        assertEquals(revision, database.revision());
        assertEquals(0L, scalar("SELECT COUNT(*) FROM payments"));
        assertEquals(0L, scalar("SELECT paid_amount FROM credits WHERE id='" + credit.getId() + "'"));
        assertEquals(0L, scalar("SELECT linked_amount FROM paylogs WHERE id='" + paylog.getId() + "'"));
        assertEquals(0L, scalar("SELECT link_count FROM paylogs WHERE id='" + paylog.getId() + "'"));
    }

    @Test
    void repairFailureRollsBackAuditAndDomainChanges() throws Exception {
        CreditEntry original = credit(new UUID(12L, 1L));
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(original, List.of(), List.of(), List.of())));
        long revision = database.revision();
        CreditEntry replacement = credit(original.getId());
        replacement.setAmountMinor(20_000L);
        DatabaseCoordinator failing = coordinatorFailingAt(DatabaseFaultInjector.FailurePoint.BEFORE_REPAIR_COMMIT);

        assertFalse(failing.repairCredit(replacement, "failure injection"));

        assertEquals(revision, database.revision());
        assertEquals(10_000L, scalar("SELECT amount FROM credits WHERE id='" + original.getId() + "'"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM legacy_records"));
    }

    @Test
    void failedPartialHealthScanDoesNotResolveAnExistingFinding() throws Exception {
        UUID creditId = insertInconsistentCredit();
        DatabaseManager.DataHealthRecord finding = database.runHealthCheck().stream()
                .filter(record -> "CREDIT_PAYMENT_TOTAL".equals(record.type())).findFirst().orElseThrow();
        execute("UPDATE credits SET paid_amount=0,status='OPEN' WHERE id='" + creditId + "'");
        DatabaseCoordinator failing = coordinatorFailingAt(DatabaseFaultInjector.FailurePoint.HEALTH_AFTER_CREDITS_BEFORE_PAYMENTS);

        assertThrows(IllegalStateException.class, failing::runHealthCheck);

        assertTrue(failing.listHealthRecords(false).stream().anyMatch(record -> finding.id().equals(record.id())));
        assertTrue(database.runHealthCheck().stream().noneMatch(record -> finding.id().equals(record.id())));
        assertTrue(database.listHealthRecords(true).stream().anyMatch(record -> finding.id().equals(record.id()) && "RESOLVED".equals(record.status())));
    }

    @Test
    void typedRepairResolvesTheRootFaultAndReopensNormalWrites() throws Exception {
        UUID creditId = insertInconsistentCredit();
        assertTrue(database.runHealthCheck().stream().anyMatch(record -> "CREDIT_PAYMENT_TOTAL".equals(record.type())));
        assertFalse(database.isSafeForWrites());
        CreditEntry replacement = credit(creditId);
        replacement.setCreatedAt(1L);

        assertTrue(database.repairCredit(replacement, "recompute exact payment aggregate"));

        assertTrue(database.listHealthRecords(false).stream().noneMatch(record -> "CREDIT_PAYMENT_TOTAL".equals(record.type())));
        assertTrue(database.isSafeForWrites());
    }

    @Test
    void transientStartupFailureNeverQuarantinesOrRestoresTheActiveTimeline() throws Exception {
        CreditEntry credit = credit(new UUID(13L, 1L));
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
        assertTrue(database.createBackup());
        Path active = FileManager.getDatabaseStorageFile();
        byte[] checksumSource = Files.readAllBytes(active);
        DatabaseCoordinator transientFailure = new DatabaseCoordinator(point -> {
            if (point == DatabaseFaultInjector.FailurePoint.STARTUP_AFTER_OPEN_BEFORE_SCHEMA_VALIDATION) {
                throw new SQLException("transient timeout", "HYT00", 50_000);
            }
        });

        transientFailure.initialize();

        assertEquals(DatabaseManager.DatabaseAvailability.WRITE_LOCKED, transientFailure.availability());
        assertTrue(transientFailure.isWriteLocked());
        assertArrayEquals(checksumSource, Files.readAllBytes(active));
        assertFalse(Files.isDirectory(FileManager.getQuarantineDirectory()));
    }

    @Test
    void openErrorBlocksEveryNormalMutationWhileRepairModeRemainsControlled() throws Exception {
        CreditEntry credit = credit(new UUID(14L, 1L));
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
        execute("INSERT INTO data_health_records (id,record_type,severity,title,message,status,created_at) VALUES ('" + UUID.randomUUID() + "','TEST_OPEN','ERROR','test','test','OPEN',1)");
        long revision = database.revision();
        long paylogs = scalar("SELECT COUNT(*) FROM paylogs");
        credit.setNote("blocked");
        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", 1_000L);
        paylog.setRawText("blocked paylog");

        assertFalse(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
        assertFalse(database.addPaylog(paylog));
        assertEquals(0, database.addPaylogsBatchDetailed(List.of(paylog)).inserted());
        assertEquals(revision, database.revision());
        assertEquals(paylogs, scalar("SELECT COUNT(*) FROM paylogs"));

        CreditEntry repaired = credit(credit.getId());
        repaired.setAmountMinor(20_000L);
        assertTrue(database.repairCredit(repaired, "controlled repair"));
        assertEquals(20_000L, scalar("SELECT amount FROM credits WHERE id='" + credit.getId() + "'"));
    }

    @Test
    void halfImportedLegacyTransactionRollsBackWithoutPendingOrCompletedState() throws Exception {
        CreditEntry legacy = credit(new UUID(15L, 1L));
        DatabaseCoordinator failing = coordinatorFailingAt(DatabaseFaultInjector.FailurePoint.LEGACY_AFTER_DOMAIN_INSERT);

        DatabaseManager.AutomaticMigrationResult result = failing.importLegacyAutomatically(
                new DatabaseManager.DatabaseState(List.of(legacy), List.of(), List.of()), List.of(), List.of(), "failure injection");

        assertFalse(result.success());
        assertEquals(0L, scalar("SELECT COUNT(*) FROM credits"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM migration_log"));
        assertEquals(0L, database.revision());
        assertFalse(database.hasPendingAutomaticJsonMigration());
        assertFalse(database.hasCompletedAutomaticJsonMigration());
    }

    private DatabaseCoordinator coordinatorFailingAt(DatabaseFaultInjector.FailurePoint failurePoint) {
        return new DatabaseCoordinator(point -> {
            if (point == failurePoint) throw new SQLException("injected " + point);
        });
    }

    private CreditEntry credit(UUID id) {
        CreditEntry credit = new CreditEntry(id, "test-deal", "creditor", "debtor", 10_000L, null, null);
        credit.setCreatedAt(1L);
        return credit;
    }

    private UUID insertInconsistentCredit() throws Exception {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO credits (id,deal_name,creditor,debtor,amount,paid_amount,created_at,status,archived,revision) VALUES ('" + id + "','bad','creditor','debtor',10000,1,1,'PARTIAL',FALSE,0)");
        return id;
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE");
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
