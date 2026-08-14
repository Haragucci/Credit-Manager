package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConnectionFactoryExistingTest {
    @TempDir Path temporary;
    private final DatabaseConnectionFactory factory = new DatabaseConnectionFactory();

    @BeforeEach
    void loadDriver() throws Exception {
        factory.loadDriver();
    }

    @Test
    void freshCreationIsExplicitAndRefusesAnExistingTarget() throws Exception {
        Path base = temporary.resolve("fresh");
        try (var connection = factory.createFresh(base)) {
            assertFalse(connection.isClosed());
        }

        assertTrue(Files.isRegularFile(temporary.resolve("fresh.mv.db")));
        assertThrows(SQLException.class, () -> factory.createFresh(base));
    }

    @Test
    void existingReadWriteOpenNeverCreatesAMissingDatabase() {
        Path base = temporary.resolve("missing");

        assertThrows(SQLException.class, () -> factory.openExistingReadWrite(base));
        assertFalse(Files.exists(temporary.resolve("missing.mv.db")));
    }

    @Test
    void existingReadOnlyOpenNeverCreatesAMissingDatabase() {
        Path base = temporary.resolve("missing-readonly");

        assertThrows(SQLException.class, () -> factory.openExistingReadOnly(base));
        assertFalse(Files.exists(temporary.resolve("missing-readonly.mv.db")));
    }

    @Test
    void existingConnectionsOpenARealDatabaseWithSpacesAndUnicode() throws Exception {
        Path base = temporary.resolve("database escaped ü");
        try (var connection = factory.createFresh(base); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample(id INT PRIMARY KEY)");
        }
        try (var connection = factory.openExistingReadWrite(base); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sample VALUES (1)");
        }
        try (var connection = factory.openExistingReadOnly(base); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM sample")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void unsafeJdbcDelimiterInPathFailsClosedWithoutCreatingATruncatedDatabase() {
        Path base = temporary.resolve("database;escaped");

        assertThrows(SQLException.class, () -> factory.createFresh(base));
        assertFalse(Files.exists(temporary.resolve("database.mv.db")));
        assertFalse(Files.exists(temporary.resolve("database;escaped.mv.db")));
    }
}
