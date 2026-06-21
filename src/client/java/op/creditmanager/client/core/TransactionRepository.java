package op.creditmanager.client.core;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.util.List;

/** Database-backed Paylog facade.  It intentionally never retains the full table in memory. */
public final class TransactionRepository {
    private static final TransactionRepository INSTANCE = new TransactionRepository();
    private long revision;
    private boolean recoveryRequired;

    private TransactionRepository() { }
    public static TransactionRepository getInstance() { return INSTANCE; }

    public synchronized void load() {
        try {
            DatabaseManager.getInstance().initialize();
            revision = DatabaseManager.getInstance().revision();
            recoveryRequired = !DatabaseManager.getInstance().isHealthy();
        } catch (RuntimeException exception) {
            recoveryRequired = true;
            CreditManagerClient.LOGGER.error("Could not initialise Paylog database access", exception);
        }
    }

    public synchronized boolean add(TransactionEntry entry) {
        if (entry == null || recoveryRequired) return false;
        boolean saved = DatabaseManager.getInstance().addPaylog(entry);
        if (saved) revision = DatabaseManager.getInstance().revision();
        return saved;
    }

    /** The default view is deliberately limited to the newest 500 rows. */
    public List<TransactionEntry> getAll() { return query("", 0, "", 500, 0); }
    public List<TransactionEntry> query(String player, int direction, String query, int limit, int offset) {
        return DatabaseManager.getInstance().queryPaylogs(player, direction, query, limit, offset);
    }
    public DatabaseManager.QueryPage<TransactionEntry> queryPage(String player, int direction, String query, int limit, int offset) {
        return DatabaseManager.getInstance().queryPaylogPage(player, direction, query, limit, offset);
    }
    public synchronized long getRevision() { return revision; }
    public synchronized boolean isWritable() { return !recoveryRequired && DatabaseManager.getInstance().isHealthy(); }
    public synchronized boolean resetCorruptTransactionsWithBackup() { return false; }
}
