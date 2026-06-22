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

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
