package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

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
    void completeVersionSevenDatabaseMigratesThroughStagingWithExactLinks() throws Exception {
        useTemporaryDataDirectory();
        createVersionSevenDatabase();

        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();

        assertTrue(manager.isHealthy());
        assertEquals(DatabaseManager.SCHEMA_VERSION, schemaVersion());
        assertEquals("bigint", columnType("credits", "amount"));
        assertEquals("bigint", columnType("payments", "amount"));
        assertEquals("bigint", columnType("paylogs", "amount"));
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT amount,linked_amount,link_count FROM paylogs WHERE id='00000000-0000-0000-0000-000000000002'")) {
            assertTrue(rows.next());
            assertEquals(10_025L, rows.getLong("amount"));
            assertEquals(4_000L, rows.getLong("linked_amount"));
            assertEquals(1, rows.getInt("link_count"));
        }
        try (var files = Files.list(FileManager.getBackupDirectory())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("creditmanager_pre_v8_")));
        }
    }

    @Test
    void missingCoreTableInExistingDatabaseIsNeverBootstrappedSilently() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE payments");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertFalse(tableExists("payments"));
    }

    @Test
    void missingForeignKeyIsRejectedBySchemaValidation() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payments DROP CONSTRAINT fk_payments_credit");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    @Test
    void missingUniquePaylogHashConstraintIsRejected() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl());
             var find = connection.prepareStatement("SELECT constraint_name FROM information_schema.table_constraints WHERE table_name='paylogs' AND constraint_type='UNIQUE'");
             var result = find.executeQuery()) {
            assertTrue(result.next());
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE paylogs DROP CONSTRAINT \"" + result.getString(1) + "\"");
            }
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    @Test
    void wrongMoneyTypeAndNullableMoneyColumnAreRejected() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE credits DROP CONSTRAINT ck_credits_paid");
            statement.execute("ALTER TABLE credits DROP CONSTRAINT ck_credits_amount");
            statement.execute("ALTER TABLE credits ALTER COLUMN amount DOUBLE PRECISION");
            statement.execute("ALTER TABLE payments DROP CONSTRAINT ck_payments_amount");
            statement.execute("ALTER TABLE payments ALTER COLUMN amount SET NULL");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    @Test
    void wrongPaylogForeignKeyDeleteRuleIsRejected() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payments DROP CONSTRAINT fk_payments_paylog");
            statement.execute("ALTER TABLE payments ADD CONSTRAINT fk_payments_paylog_wrong FOREIGN KEY (paylog_id) REFERENCES paylogs(id) ON DELETE CASCADE");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    private void createVersionSevenDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata (meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            statement.execute("CREATE TABLE schema_migrations (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB, search_text CLOB, completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
            statement.execute("CREATE TABLE paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL, metadata CLOB, linked_amount DOUBLE PRECISION NOT NULL DEFAULT 0, link_count INT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64), paylog_id VARCHAR(36), note CLOB, revision BIGINT NOT NULL)");
            statement.execute("CREATE TABLE credit_events (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_after DOUBLE PRECISION NOT NULL, remaining_after DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, deal_name VARCHAR(256), creditor VARCHAR(64), debtor VARCHAR(64), note CLOB, amount_before DOUBLE PRECISION NOT NULL, amount_after DOUBLE PRECISION NOT NULL, actor VARCHAR(64), source VARCHAR(64), item_payment BOOLEAN NOT NULL, revision BIGINT NOT NULL)");
            statement.execute("INSERT INTO metadata VALUES ('schema_version','7'),('data_revision','1')");
            statement.execute("INSERT INTO credits VALUES ('00000000-0000-0000-0000-000000000001','deal','creditor','debtor',100.25,40,1,NULL,'PARTIAL',NULL,'',NULL,FALSE,1)");
            statement.execute("INSERT INTO paylogs VALUES ('00000000-0000-0000-0000-000000000002','debtor','creditor',100.25,'legacy','legacy',1,'legacy-hash','DETECTED',1,NULL,0,0)");
            statement.execute("INSERT INTO payments VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','debtor','creditor',40,'[]',NULL,'[]',1,'PAYLOG_DETECTED','00000000-0000-0000-0000-000000000002',NULL,1)");
            statement.execute("INSERT INTO credit_events VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001','PAYMENT_ADDED',40,40,60.25,1,'deal','creditor','debtor',NULL,100.25,60.25,'debtor','PAYLOG_DETECTED',FALSE,1)");
        }
    }

    private void useTemporaryDataDirectory() throws Exception {
        previousDirectory = (Path) dataDirectoryField().get(null);
        Files.createDirectories(dataDirectory);
        dataDirectoryField().set(null, dataDirectory);
        resetDatabaseInitialization();
    }

    private void resetDatabaseInitialization() throws Exception {
        Object coordinator = coordinator();
        set(coordinator, "initialized", false);
        set(coordinator, "initializedAt", null);
        set(coordinator, "healthy", true);
        set(coordinator, "writeLocked", false);
        set(coordinator, "availability", DatabaseManager.DatabaseAvailability.UNKNOWN);
    }

    private Object coordinator() throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("coordinator");
        field.setAccessible(true);
        return field.get(DatabaseManager.getInstance());
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private int schemaVersion() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT meta_value FROM metadata WHERE meta_key='schema_version'")) {
            result.next();
            return Integer.parseInt(result.getString(1));
        }
    }

    private String columnType(String table, String column) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.prepareStatement("SELECT data_type FROM information_schema.columns WHERE table_name=? AND column_name=?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_name=?")) {
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
