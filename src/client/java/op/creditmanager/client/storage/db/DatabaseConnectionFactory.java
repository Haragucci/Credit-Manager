package op.creditmanager.client.storage.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

final class DatabaseConnectionFactory {
    void loadDriver() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
    }

    Connection open(Path databaseBase) throws SQLException {
        String path = databaseBase.toAbsolutePath().normalize().toString().replace('\\', '/').replace(";", "\\;");
        return DriverManager.getConnection("jdbc:h2:file:" + path + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE");
    }

    Connection openReadOnly(Path databaseBase) throws SQLException {
        String path = databaseBase.toAbsolutePath().normalize().toString().replace('\\', '/').replace(";", "\\;");
        return DriverManager.getConnection("jdbc:h2:file:" + path + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE;ACCESS_MODE_DATA=r;IFEXISTS=TRUE");
    }
}
