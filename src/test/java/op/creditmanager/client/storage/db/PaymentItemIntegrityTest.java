package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.PaymentKind;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentItemIntegrityTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;
    private DatabaseCoordinator database;
    private CreditEntry credit;

    @BeforeEach
    void initializeDatabase() throws Exception {
        Field field = dataDirectoryField();
        previousDirectory = (Path) field.get(null);
        field.set(null, dataDirectory);
        FileManager.initialize();
        database = new DatabaseCoordinator();
        database.initialize();
        credit = new CreditEntry(UUID.randomUUID(), "item-test", "creditor", "debtor", 10_000L, null, null);
        credit.setCreatedAt(1L);
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
    }

    @AfterEach
    void restoreDataDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        DataHealth.clearReasons();
    }

    @Test
    void malformedItemJsonCreatesRawHealthFindingAndNeverLoadsAsEmptyItems() throws Exception {
        UUID payment = UUID.randomUUID();
        execute("UPDATE credits SET paid_amount=100,status='PARTIAL' WHERE id='" + credit.getId() + "'");
        execute("INSERT INTO payments (id,credit_id,from_player,to_player,amount,payment_kind,items_json,item_nbt_entries,created_at,source,revision) VALUES ('" + payment + "','" + credit.getId() + "','debtor','creditor',100,'ITEM','{bad','[]',1,'MANUAL',0)");

        DatabaseManager.DataHealthRecord finding = database.runHealthCheck().stream()
                .filter(record -> "PAYMENT_ITEMS_JSON".equals(record.type()) && payment.toString().equals(record.sourceId()))
                .findFirst().orElseThrow();

        assertTrue(finding.rawPayload().contains("{bad"));
        assertFalse(database.isSafeForWrites());
        assertThrows(IllegalStateException.class, database::loadCreditState);
    }

    @Test
    void validEmptyMoneyPayloadRemainsAValidMoneyPayment() throws Exception {
        UUID payment = UUID.randomUUID();
        execute("UPDATE credits SET paid_amount=100,status='PARTIAL' WHERE id='" + credit.getId() + "'");
        execute("INSERT INTO payments (id,credit_id,from_player,to_player,amount,payment_kind,items_json,item_nbt_entries,created_at,source,revision) VALUES ('" + payment + "','" + credit.getId() + "','debtor','creditor',100,'MONEY','[]','[]',1,'MANUAL',0)");

        assertTrue(database.runHealthCheck().stream().noneMatch(record -> record.type().startsWith("PAYMENT_ITEM")));
        var loaded = database.loadCreditState().payments().getFirst();
        assertEquals(PaymentKind.MONEY, loaded.getPaymentKind());
        assertTrue(loaded.getItems().isEmpty());
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE"); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
