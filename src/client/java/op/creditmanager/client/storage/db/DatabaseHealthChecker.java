package op.creditmanager.client.storage.db;

import java.util.List;

public final class DatabaseHealthChecker {
    private static final DatabaseHealthChecker INSTANCE = new DatabaseHealthChecker(DatabaseManager.getInstance());
    private final DatabaseManager database;

    private DatabaseHealthChecker(DatabaseManager database) { this.database = database; }
    public static DatabaseHealthChecker getInstance() { return INSTANCE; }

    public List<DatabaseManager.DataHealthRecord> check() { return database.runHealthCheck(); }
    public List<DatabaseManager.DataHealthRecord> openFindings() { return database.listHealthRecords(false); }
}
