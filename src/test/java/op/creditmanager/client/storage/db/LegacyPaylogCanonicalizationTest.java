package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPaylogCanonicalizationTest {
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
    void restoreDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        DataHealth.clearReasons();
    }

    @Test
    void equivalentLegacyPaylogUuidMapsPaymentToExistingCanonicalId() throws Exception {
        TransactionEntry canonical = paylog(new UUID(30L, 1L));
        assertTrue(database.addPaylog(canonical));
        TransactionEntry legacy = paylog(new UUID(30L, 2L));
        CreditEntry credit = credit(new UUID(30L, 3L), 4_000L);
        Payment payment = linkedPayment(new UUID(30L, 4L), credit, legacy.getId(), "note");

        DatabaseManager.AutomaticMigrationResult result = database.importLegacyAutomatically(
                new DatabaseManager.DatabaseState(List.of(credit), List.of(payment), List.of()), List.of(legacy), List.of(), "canonical mapping");

        assertTrue(result.success());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM paylogs"));
        assertEquals(canonical.getId().toString(), stringScalar("SELECT paylog_id FROM payments WHERE id='" + payment.getId() + "'"));
        assertEquals(4_000L, scalar("SELECT linked_amount FROM paylogs WHERE id='" + canonical.getId() + "'"));
        assertEquals(1L, scalar("SELECT link_count FROM paylogs WHERE id='" + canonical.getId() + "'"));
    }

    @Test
    void wrongDirectionCannotCommitLegacyPaylogGraph() throws Exception {
        TransactionEntry paylog = paylog(new UUID(31L, 1L));
        CreditEntry credit = credit(new UUID(31L, 2L), 4_000L);
        Payment payment = linkedPayment(new UUID(31L, 3L), credit, paylog.getId(), "note");
        payment.setFromPlayer("creditor");
        payment.setToPlayer("debtor");

        DatabaseManager.AutomaticMigrationResult result = database.importLegacyAutomatically(
                new DatabaseManager.DatabaseState(List.of(credit), List.of(payment), List.of()), List.of(paylog), List.of(), "wrong direction");

        assertFalse(result.success());
        assertEquals(0L, scalar("SELECT COUNT(*) FROM credits"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM payments"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM paylogs"));
    }

    @Test
    void noteOnlyDifferenceIsPreservedAsDistinctLegacyRecord() throws Exception {
        TransactionEntry paylog = paylog(new UUID(32L, 1L));
        assertTrue(database.addPaylog(paylog));
        CreditEntry credit = credit(new UUID(32L, 2L), 4_000L);
        Payment existing = linkedPayment(new UUID(32L, 3L), credit, paylog.getId(), "first");
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(existing), List.of(), List.of())));
        Payment incoming = linkedPayment(existing.getId(), credit, paylog.getId(), "second");

        assertTrue(database.importLegacyAutomatically(new DatabaseManager.DatabaseState(List.of(credit), List.of(incoming), List.of()), List.of(), List.of(), "note identity").success());

        assertEquals("first", stringScalar("SELECT note FROM payments WHERE id='" + existing.getId() + "'"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM legacy_records WHERE record_kind='PAYMENT'"));
    }

    @Test
    void paylogIdOnlyDifferenceIsPreservedAsDistinctLegacyRecord() throws Exception {
        TransactionEntry first = paylog(new UUID(33L, 1L));
        TransactionEntry second = paylog(new UUID(33L, 2L));
        second.setTimestamp(first.getTimestamp() + 1L);
        second.setRawText("second paylog");
        assertTrue(database.addPaylog(first));
        assertTrue(database.addPaylog(second));
        CreditEntry credit = credit(new UUID(33L, 3L), 4_000L);
        Payment existing = linkedPayment(new UUID(33L, 4L), credit, first.getId(), "note");
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(existing), List.of(), List.of())));
        Payment incoming = linkedPayment(existing.getId(), credit, second.getId(), "note");

        assertTrue(database.importLegacyAutomatically(new DatabaseManager.DatabaseState(List.of(credit), List.of(incoming), List.of()), List.of(), List.of(), "paylog identity").success());

        assertEquals(first.getId().toString(), stringScalar("SELECT paylog_id FROM payments WHERE id='" + existing.getId() + "'"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM legacy_records WHERE record_kind='PAYMENT'"));
    }

    private TransactionEntry paylog(UUID id) {
        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", 10_000L);
        paylog.setId(id);
        paylog.setTimestamp(1_000L);
        paylog.setRawText("canonical paylog");
        paylog.setSource("DETECTED");
        paylog.setMetadata("{}");
        return paylog;
    }

    private CreditEntry credit(UUID id, long paid) {
        CreditEntry credit = new CreditEntry(id, "legacy-deal", "creditor", "debtor", 10_000L, null, null);
        credit.setCreatedAt(1L);
        credit.setPaidAmountMinor(paid);
        credit.setStatus(paid == 0L ? "OPEN" : paid == 10_000L ? "PAID" : "PARTIAL");
        return credit;
    }

    private Payment linkedPayment(UUID id, CreditEntry credit, UUID paylogId, String note) {
        Payment payment = new Payment(credit.getId(), "debtor", "creditor", 4_000L, List.of(), "PAYLOG_MIGRATION");
        payment.setId(id);
        payment.setTimestamp(2_000L);
        payment.setPaylogId(paylogId);
        payment.setNote(note);
        return payment;
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String stringScalar(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
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
