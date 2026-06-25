package op.creditmanager.client.storage.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class DatabaseMetadataDao {
    public String read(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM metadata WHERE meta_key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    public void write(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO metadata (meta_key, meta_value) KEY(meta_key) VALUES (?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    public long revision(Connection connection) throws SQLException {
        String value = read(connection, "data_revision");
        return value == null ? 0L : Long.parseLong(value);
    }

    public long nextRevision(Connection connection) throws SQLException {
        return revision(connection) + 1L;
    }

    public void bumpRevision(Connection connection) throws SQLException {
        write(connection, "data_revision", String.valueOf(nextRevision(connection)));
    }
}
