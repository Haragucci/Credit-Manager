package op.creditmanager.client.core;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.cache.BoundedQueryCache;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TransactionRepository {
    private static final TransactionRepository INSTANCE = new TransactionRepository();
    private long revision;
    private boolean recoveryRequired;
    private final BoundedQueryCache<PaylogQuery, DatabaseManager.QueryPage<TransactionEntry>> queryCache = new BoundedQueryCache<>(128);

    private TransactionRepository() { }
    public static TransactionRepository getInstance() { return INSTANCE; }

    public synchronized void load() {
        try {
            DatabaseManager database = DatabaseManager.getInstance();
            database.initialize();
            if (database.requiresUserRecovery() || !database.isHealthy()) {
                recoveryRequired = true;
                return;
            }
            revision = database.revision();
            recoveryRequired = false;
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

    public List<TransactionEntry> getAll() { return query("", 0, "", 500, 0); }
    public List<TransactionEntry> query(String player, int direction, String query, int limit, int offset) {
        return queryPage(player, direction, query, limit, offset).entries();
    }
    public DatabaseManager.QueryPage<TransactionEntry> queryPage(String player, int direction, String query, int limit, int offset) {
        return cachedPage(new PaylogQuery(false, player, direction, "", query, limit, offset),
                () -> DatabaseManager.getInstance().queryPaylogPage(player, direction, query, limit, offset));
    }
    public DatabaseManager.QueryPage<TransactionEntry> queryAvailableForDeal(String payer, String receiver, String query, int limit, int offset) {
        return cachedPage(new PaylogQuery(true, payer, 0, receiver, query, limit, offset),
                () -> DatabaseManager.getInstance().queryAvailablePaylogs(payer, receiver, query, limit, offset));
    }
    public Optional<TransactionEntry> find(UUID id) { return DatabaseManager.getInstance().findPaylog(id); }
    public synchronized long getRevision() { return revision; }
    public BoundedQueryCache.CacheStats queryCacheStats() { return queryCache.stats(); }
    public synchronized boolean isWritable() { return !recoveryRequired && DatabaseManager.getInstance().isHealthy(); }

    public synchronized boolean resetCorruptTransactionsWithBackup() {
        DatabaseManager database = DatabaseManager.getInstance();
        if (!recoveryRequired && database.isHealthy() && !database.isWriteLocked()) return false;
        if (database.recheckAndRepair()) {
            recoveryRequired = false;
            revision = database.revision();
            return true;
        }
        if (!database.resetCorruptDatabaseWithBackup()) return false;
        recoveryRequired = false;
        revision = database.revision();
        return true;
    }

    public synchronized boolean recheckDatabase() {
        boolean repaired = DatabaseManager.getInstance().recheckAndRepair();
        recoveryRequired = !repaired;
        if (repaired) revision = DatabaseManager.getInstance().revision();
        return repaired;
    }

    private DatabaseManager.QueryPage<TransactionEntry> cachedPage(PaylogQuery key, java.util.function.Supplier<DatabaseManager.QueryPage<TransactionEntry>> loader) {
        long currentRevision = DatabaseManager.getInstance().revision();
        DatabaseManager.QueryPage<TransactionEntry> page = queryCache.getOrCompute(currentRevision, key, () -> copyPage(loader.get()));
        return copyPage(page);
    }

    private DatabaseManager.QueryPage<TransactionEntry> copyPage(DatabaseManager.QueryPage<TransactionEntry> page) {
        List<TransactionEntry> entries = page.entries().stream().map(this::copy).toList();
        return new DatabaseManager.QueryPage<>(entries, page.totalCount(), page.offset(), page.pageSize());
    }

    private TransactionEntry copy(TransactionEntry source) {
        TransactionEntry copy = new TransactionEntry();
        copy.setId(source.getId()); copy.setFromPlayer(source.getFromPlayer()); copy.setToPlayer(source.getToPlayer()); copy.setAmount(source.getAmount()); copy.setTimestamp(source.getTimestamp()); copy.setRawText(source.getRawText()); copy.setNormalizedText(source.getNormalizedText()); copy.setSource(source.getSource()); copy.setHash(source.getHash()); copy.setMetadata(source.getMetadata()); copy.setLinkedAmount(source.getLinkedAmount());
        return copy;
    }

    private record PaylogQuery(boolean available, String player, int direction, String receiver, String query, int limit, int offset) { }
}
