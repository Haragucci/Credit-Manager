package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.FileManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseQuerySnapshotTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;

    @BeforeEach
    void useTemporaryDataDirectory() throws Exception {
        previousDirectory = (Path) dataDirectoryField().get(null);
        dataDirectoryField().set(null, dataDirectory);
        DatabaseManager.getInstance().initialize();
    }

    @AfterEach
    void restoreDataDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        resetDatabaseInitialization();
    }

    @Test
    void countAndPageReadUseOneRepeatableSnapshotAcrossAConcurrentCommit() throws Exception {
        DatabaseManager database = DatabaseManager.getInstance();
        assertTrue(database.addPaylog(paylog("first", 1L)));
        DatabaseCoordinator coordinator = coordinator();

        try (Connection snapshot = connection()) {
            coordinator.beginConsistentRead(snapshot);
            assertEquals(1L, count(snapshot));

            assertTrue(database.addPaylog(paylog("second", 2L)));

            assertEquals(1L, count(snapshot));
            assertEquals(1L, pageCount(snapshot));
        }
        assertEquals(2L, database.queryPaylogPage("", 0, "", 10, 0).totalCount());
    }

    private TransactionEntry paylog(String raw, long timestamp) {
        TransactionEntry entry = new TransactionEntry("payer", "receiver", 1_000L);
        entry.setRawText(raw);
        entry.setTimestamp(timestamp);
        entry.setSource("TEST");
        return entry;
    }

    private long count(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM paylogs")) {
            result.next();
            return result.getLong(1);
        }
    }

    private long pageCount(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM (SELECT id FROM paylogs ORDER BY created_at,id LIMIT 10 OFFSET 0)")) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE");
    }

    private DatabaseCoordinator coordinator() throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("coordinator");
        field.setAccessible(true);
        return (DatabaseCoordinator) field.get(DatabaseManager.getInstance());
    }

    private void resetDatabaseInitialization() throws Exception {
        DatabaseCoordinator coordinator = coordinator();
        set(coordinator, "initialized", false);
        set(coordinator, "initializedAt", null);
        set(coordinator, "healthy", true);
        set(coordinator, "writeLocked", false);
        set(coordinator, "availability", DatabaseManager.DatabaseAvailability.UNKNOWN);
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
