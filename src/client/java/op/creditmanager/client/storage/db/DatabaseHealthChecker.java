package op.creditmanager.client.storage.db;

import java.util.List;

/**
 * Small facade around the persistent database health scan. Keeping this
 * separate from the UI makes checks callable after startup, after imports and
 * from automated tests without touching Minecraft screen state.
 */
public final class DatabaseHealthChecker {
    private static final DatabaseHealthChecker INSTANCE = new DatabaseHealthChecker(DatabaseManager.getInstance());
    private final DatabaseManager database;

    private DatabaseHealthChecker(DatabaseManager database) { this.database = database; }
    public static DatabaseHealthChecker getInstance() { return INSTANCE; }

    public List<DatabaseManager.DataHealthRecord> check() { return database.runHealthCheck(); }
    public List<DatabaseManager.DataHealthRecord> openFindings() { return database.listHealthRecords(false); }
}
