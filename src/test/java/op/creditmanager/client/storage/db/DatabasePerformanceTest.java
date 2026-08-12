package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("performance")
class DatabasePerformanceTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;

    @BeforeEach
    void useTemporaryDataDirectory() throws Exception {
        Field field = dataDirectoryField();
        previousDirectory = (Path) field.get(null);
        field.set(null, dataDirectory);
        FileManager.initialize();
        DatabaseManager.getInstance().initialize();
    }

    @AfterEach
    void restoreDataDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        DataHealth.clearReasons();
    }

    @Test
    void tenThousandPaylogsUseOneAggregateInspectionQuery() throws Exception {
        try (Connection connection = connection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO paylogs (id,payer,receiver,amount,raw_text,normalized_text,created_at,entry_hash,source,revision,metadata,linked_amount,link_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            connection.setAutoCommit(false);
            for (int index = 0; index < 10_000; index++) {
                String id = new UUID(1L, index + 1L).toString();
                insert.setString(1, id);
                insert.setString(2, "payer");
                insert.setString(3, "receiver");
                insert.setLong(4, 10_000L);
                insert.setString(5, "performance paylog " + index);
                insert.setString(6, "performance paylog " + index);
                insert.setLong(7, index + 1L);
                insert.setString(8, id);
                insert.setString(9, "DETECTED");
                insert.setLong(10, 0L);
                insert.setString(11, null);
                insert.setLong(12, index == 9_999 ? 1L : 0L);
                insert.setInt(13, index == 9_999 ? 1 : 0);
                insert.addBatch();
                if ((index + 1) % 1_000 == 0) insert.executeBatch();
            }
            connection.commit();
        }

        List<DatabaseManager.DataHealthRecord> findings = DatabaseManager.getInstance().runHealthCheck();

        assertEquals(1, findings.stream().filter(record -> "PAYLOG_LINK_AGGREGATE".equals(record.type())).count());
        assertEquals(6, coordinator().lastHealthInspectionQueryCount());
    }

    @Test
    void hundredThousandEventsRemainUntouchedByTargetedCreditMutation() throws Exception {
        CreditEntry credit = new CreditEntry(new UUID(2L, 1L), "performance-deal", "creditor", "debtor", 10_000L, null, "before");
        assertTrue(DatabaseManager.getInstance().commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
        long revisionBefore = DatabaseManager.getInstance().revision();

        try (Connection connection = connection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO credit_events (id,credit_id,event_type,amount,paid_after,remaining_after,created_at,deal_name,creditor,debtor,note,amount_before,amount_after,actor,source,item_payment,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            connection.setAutoCommit(false);
            for (int index = 0; index < 100_000; index++) {
                insert.setString(1, new UUID(3L, index + 1L).toString());
                insert.setString(2, credit.getId().toString());
                insert.setString(3, "CREDIT_UPDATED");
                insert.setLong(4, 0L);
                insert.setLong(5, 0L);
                insert.setLong(6, 10_000L);
                insert.setLong(7, index + 1L);
                insert.setString(8, credit.getDealName());
                insert.setString(9, credit.getCreditor());
                insert.setString(10, credit.getDebtor());
                insert.setString(11, null);
                insert.setLong(12, 10_000L);
                insert.setLong(13, 10_000L);
                insert.setString(14, "creditor");
                insert.setString(15, "PERFORMANCE");
                insert.setBoolean(16, false);
                insert.setLong(17, 0L);
                insert.addBatch();
                if ((index + 1) % 2_000 == 0) insert.executeBatch();
            }
            connection.commit();
        }

        credit.setNote("after");
        assertTrue(DatabaseManager.getInstance().commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
        assertEquals(revisionBefore + 1L, DatabaseManager.getInstance().revision());
        assertEquals(100_000L, count("credit_events"));
        DatabaseManager.DatabaseState reloaded = DatabaseManager.getInstance().loadCreditState();
        assertEquals(100_000, reloaded.events().size());
        assertEquals("after", reloaded.credits().getFirst().getNote());
    }

    @Test
    void hundredThousandPaylogsUseIndexedTokenSearchForOneTwoAndThreeTokens() throws Exception {
        try (Connection connection = connection();
             PreparedStatement paylog = connection.prepareStatement("INSERT INTO paylogs (id,payer,receiver,amount,raw_text,normalized_text,created_at,entry_hash,source,revision,metadata,linked_amount,link_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement token = connection.prepareStatement("INSERT INTO paylog_search_tokens (paylog_id,token) VALUES (?,?)")) {
            connection.setAutoCommit(false);
            for (int index = 0; index < 100_000; index++) {
                String id = new UUID(4L, index + 1L).toString();
                paylog.setString(1, id);
                paylog.setString(2, "payer" + index);
                paylog.setString(3, "receiver");
                paylog.setLong(4, index + 100L);
                paylog.setString(5, "representative history " + index);
                paylog.setString(6, "representative history " + index);
                paylog.setLong(7, index + 1L);
                paylog.setString(8, id);
                paylog.setString(9, "DETECTED");
                paylog.setLong(10, 0L);
                paylog.setString(11, null);
                paylog.setLong(12, 0L);
                paylog.setInt(13, 0);
                paylog.addBatch();
                if (index % 1_000 == 0) addToken(token, id, "needle");
                if (index % 2_000 == 0) addToken(token, id, "alpha");
                if (index % 5_000 == 0) addToken(token, id, "omega");
                if ((index + 1) % 2_000 == 0) {
                    paylog.executeBatch();
                    token.executeBatch();
                }
            }
            connection.commit();
        }

        assertEquals(100L, DatabaseManager.getInstance().queryPaylogPage("", 0, "needle", 500, 0).totalCount());
        assertEquals(50L, DatabaseManager.getInstance().queryPaylogPage("", 0, "needle alpha", 500, 0).totalCount());
        DatabaseManager.QueryPage<?> triple = DatabaseManager.getInstance().queryPaylogPage("", 0, "needle alpha omega", 500, 0);
        assertEquals(10L, triple.totalCount());
        assertEquals(10, triple.entries().size());

        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("EXPLAIN SELECT paylog_id FROM paylog_search_tokens WHERE token='needle'")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).toUpperCase(Locale.ROOT).contains("IDX_PAYLOG_SEARCH_TOKEN"));
        }
    }

    private void addToken(PreparedStatement statement, String paylogId, String token) throws Exception {
        statement.setString(1, paylogId);
        statement.setString(2, token);
        statement.addBatch();
    }

    private long count(String table) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE");
    }

    private DatabaseCoordinator coordinator() throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("coordinator");
        field.setAccessible(true);
        return (DatabaseCoordinator) field.get(DatabaseManager.getInstance());
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
