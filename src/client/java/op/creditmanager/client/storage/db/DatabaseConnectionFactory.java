package op.creditmanager.client.storage.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

final class DatabaseConnectionFactory {
    private final AtomicLong openedConnections = new AtomicLong();

    void loadDriver() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
    }

    Connection createFresh(Path databaseBase) throws SQLException {
        requireMissing(databaseBase);
        return open(url(databaseBase, false, false));
    }

    Connection openExistingReadWrite(Path databaseBase) throws SQLException {
        requireExisting(databaseBase);
        return open(url(databaseBase, true, false));
    }

    Connection openExistingReadOnly(Path databaseBase) throws SQLException {
        requireExisting(databaseBase);
        return open(url(databaseBase, true, true));
    }

    long openedConnectionCount() {
        return openedConnections.get();
    }

    private Connection open(String url) throws SQLException {
        openedConnections.incrementAndGet();
        return DriverManager.getConnection(url);
    }

    private String url(Path databaseBase, boolean ifExists, boolean readOnly) throws SQLException {
        String path = databaseBase.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (path.indexOf(';') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new SQLException("Unsupported character in H2 database path", "90046");
        }
        return "jdbc:h2:file:" + path + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE"
                + (readOnly ? ";ACCESS_MODE_DATA=r" : "") + (ifExists ? ";IFEXISTS=TRUE" : "");
    }

    private void requireMissing(Path databaseBase) throws SQLException {
        if (java.nio.file.Files.exists(storagePath(databaseBase))) {
            throw new SQLException("Fresh database target already exists", "90028");
        }
    }

    private void requireExisting(Path databaseBase) throws SQLException {
        if (!java.nio.file.Files.isRegularFile(storagePath(databaseBase))) {
            throw new SQLException("Existing database file is missing", "90013");
        }
    }

    private Path storagePath(Path databaseBase) {
        return databaseBase.resolveSibling(databaseBase.getFileName() + ".mv.db");
    }
}
