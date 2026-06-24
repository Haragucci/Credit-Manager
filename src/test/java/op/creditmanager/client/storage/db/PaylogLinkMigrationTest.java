package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaylogLinkMigrationTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;

    @AfterEach
    void restoreDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
    }

    @Test
    void schemaVersionFourAddsTheNullablePaylogReferenceWithoutTouchingExistingPayments() throws Exception {
        previousDirectory = (Path) dataDirectoryField().get(null);
        Files.createDirectories(dataDirectory);
        dataDirectoryField().set(null, dataDirectory);
        String jdbc = "jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE";
        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata (meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            statement.execute("CREATE TABLE schema_migrations (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB, completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
            statement.execute("CREATE TABLE payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64), revision BIGINT NOT NULL)");
            statement.execute("CREATE TABLE paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL, metadata CLOB)");
            statement.execute("INSERT INTO metadata (meta_key, meta_value) VALUES ('schema_version', '4')");
            statement.execute("INSERT INTO payments (id, credit_id, amount, created_at, revision) VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 12.5, 1, 0)");
        }

        DatabaseManager.getInstance().initialize();

        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT paylog_id, note FROM payments WHERE id='00000000-0000-0000-0000-000000000001'")) {
            assertTrue(result.next());
            assertEquals(null, result.getString("paylog_id"));
            assertEquals(null, result.getString("note"));
        }
        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT meta_value FROM metadata WHERE meta_key='schema_version'")) {
            assertTrue(result.next());
            assertEquals(String.valueOf(DatabaseManager.SCHEMA_VERSION), result.getString(1));
        }
    }

    @Test
    void schemaVersionSixRepairsMissingSearchTextAndHistoryStillFindsExistingDeals() throws Exception {
        useTemporaryDataDirectory();
        createVersionSixDriftDatabase(true, false, false);

        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();

        assertTrue(columnExists("credits", "search_text"));
        var history = manager.queryDealHistoryPage("creditor", "legacy deal", false,
                DatabaseManager.DealHistorySort.NEWEST, 50, 0);
        assertEquals(1, history.entries().size());
        assertEquals("Legacy deal", history.entries().getFirst().getDealName());
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT search_text FROM credits WHERE id='00000000-0000-0000-0000-000000000011'")) {
            assertTrue(result.next());
            assertFalse(result.getString(1).isBlank());
        }
    }

    @Test
    void schemaVersionSixRepairsMissingPaylogColumnsAndPaylogQueriesRemainUsable() throws Exception {
        useTemporaryDataDirectory();
        createVersionSixDriftDatabase(false, true, true);

        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();

        assertTrue(columnExists("payments", "paylog_id"));
        assertTrue(columnExists("payments", "note"));
        assertTrue(columnExists("paylogs", "metadata"));
        assertEquals(1, manager.queryPaylogPage("debtor", 0, "legacy", 50, 0).entries().size());
        assertEquals(1, manager.queryAvailablePaylogs("debtor", "creditor", "legacy", 50, 0).entries().size());
        assertTrue(manager.findPaylog(UUID.fromString("00000000-0000-0000-0000-000000000012")).isPresent());
    }

    @Test
    void schemaVersionSixMigratesPaylogLinkCachesAndSearchTokens() throws Exception {
        useTemporaryDataDirectory();
        createVersionSixDriftDatabase(false, false, false);

        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();

        assertTrue(columnExists("paylogs", "linked_amount"));
        assertTrue(columnExists("paylogs", "link_count"));
        assertTrue(tableExists("paylog_search_tokens"));
        assertEquals(1, manager.queryPaylogPage("debtor", 0, "legacy", 50, 0).entries().size());
    }

    @Test
    void runtimeSchemaDriftIsRepairedBeforeThePaylogQueryIsRetried() throws Exception {
        useTemporaryDataDirectory();
        createVersionSixDriftDatabase(false, false, false);
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payments DROP COLUMN paylog_id");
        }

        assertEquals(1, manager.queryPaylogPage("debtor", 0, "", 50, 0).entries().size());
        assertTrue(columnExists("payments", "paylog_id"));
        assertTrue(manager.isHealthy());
        assertFalse(manager.isWriteLocked());
    }

    @Test
    void runtimeSchemaDriftIsRepairedBeforeTheHistoryQueryIsRetried() throws Exception {
        useTemporaryDataDirectory();
        createVersionSixDriftDatabase(false, false, false);
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE credits DROP COLUMN search_text");
        }

        assertEquals(1, manager.queryDealHistoryPage("creditor", "legacy", false,
                DatabaseManager.DealHistorySort.NEWEST, 50, 0).entries().size());
        assertTrue(columnExists("credits", "search_text"));
        assertTrue(manager.isHealthy());
        assertFalse(manager.isWriteLocked());
    }

    private void useTemporaryDataDirectory() throws Exception {
        previousDirectory = (Path) dataDirectoryField().get(null);
        Files.createDirectories(dataDirectory);
        dataDirectoryField().set(null, dataDirectory);
    }

    private void createVersionSixDriftDatabase(boolean missingSearchText, boolean missingPaylogId, boolean missingPaylogMetadata) throws Exception {
        String searchText = missingSearchText ? "" : ", search_text CLOB";
        String paylogId = missingPaylogId ? "" : ", paylog_id VARCHAR(36)";
        String paylogMetadata = missingPaylogMetadata ? "" : ", metadata CLOB";
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata (meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            statement.execute("CREATE TABLE schema_migrations (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB" + searchText + ", completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
            statement.execute("CREATE TABLE payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64)" + paylogId + ", revision BIGINT NOT NULL)");
            statement.execute("CREATE TABLE paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL" + paylogMetadata + ")");
            statement.execute("INSERT INTO metadata (meta_key, meta_value) VALUES ('schema_version', '6')");
            statement.execute("INSERT INTO credits (id, deal_name, creditor, debtor, amount, paid_amount, created_at, status, note, completed_at, archived, revision) VALUES ('00000000-0000-0000-0000-000000000011', 'Legacy deal', 'creditor', 'debtor', 25, 25, 1, 'PAID', 'legacy note', 2, FALSE, 0)");
            statement.execute("INSERT INTO paylogs (id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, revision) VALUES ('00000000-0000-0000-0000-000000000012', 'debtor', 'creditor', 12.5, 'legacy paylog', 'legacy paylog', 1, 'legacy-paylog', 'MANUAL', 0)");
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME)=? AND LOWER(COLUMN_NAME)=?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)=?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }

    private String jdbcUrl() {
        return "jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE";
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
