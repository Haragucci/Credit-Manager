package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DatabaseManager {
    public static final int SCHEMA_VERSION = DatabaseCoordinator.SCHEMA_VERSION;
    public static final int PAGE_SIZE = DatabaseCoordinator.PAGE_SIZE;
    private static final DatabaseManager INSTANCE = new DatabaseManager();
    private final DatabaseCoordinator coordinator = new DatabaseCoordinator();

    private DatabaseManager() { }

    public static DatabaseManager getInstance() { return INSTANCE; }
    public DatabaseAvailability availability() { return coordinator.availability(); }
    public boolean requiresUserRecovery() { return coordinator.requiresUserRecovery(); }
    public void initialize() { coordinator.initialize(); }
    public boolean createEmptyDatabaseAfterPhysicalRecovery() { return coordinator.createEmptyDatabaseAfterPhysicalRecovery(); }
    public boolean isHealthy() { return coordinator.isHealthy(); }
    public boolean isWriteLocked() { return coordinator.isWriteLocked(); }
    public boolean isSafeForWrites() { return coordinator.isSafeForWrites(); }
    public boolean recheckAndRepair() { return coordinator.recheckAndRepair(); }
    public long revision() { return coordinator.revision(); }
    public boolean hasDomainData() { return coordinator.hasDomainData(); }
    public boolean hasCompletedJsonMigration() { return coordinator.hasCompletedJsonMigration(); }
    public boolean hasCompletedAutomaticJsonMigration() { return coordinator.hasCompletedAutomaticJsonMigration(); }
    public boolean createBackup() { return coordinator.createBackup(); }
    public DatabaseState loadCreditState() { return coordinator.loadCreditState(); }
    public boolean replaceCreditState(Collection<CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) { return coordinator.replaceCreditState(credits, payments, events); }
    public boolean commitCreditMutation(CreditMutation mutation) { return coordinator.commitCreditMutation(mutation); }
    public boolean commitCreditMutationsBatch(Collection<CreditMutation> mutations) { return coordinator.commitCreditMutationsBatch(mutations); }
    public boolean importLegacy(DatabaseState state, Collection<TransactionEntry> paylogs, String details) { return coordinator.importLegacy(state, paylogs, details); }
    public AutomaticMigrationResult importLegacyAutomatically(DatabaseState state, Collection<TransactionEntry> paylogs, Collection<LegacyRecord> preserved, String details) { return coordinator.importLegacyAutomatically(state, paylogs, preserved, details); }
    public int legacyRecordCount() { return coordinator.legacyRecordCount(); }
    public boolean addPaylog(TransactionEntry entry) { return coordinator.addPaylog(entry); }
    public boolean resetCorruptDatabaseWithBackup() { return coordinator.resetCorruptDatabaseWithBackup(); }
    public boolean restoreLatestValidBackup() { return coordinator.restoreLatestValidBackup(); }
    public List<BackupManifestEntry> listBackups() { return coordinator.listBackups(); }
    public Optional<TransactionEntry> findPaylog(UUID id) { return coordinator.findPaylog(id); }
    public int addPaylogsBatch(Collection<TransactionEntry> entries) { return coordinator.addPaylogsBatch(entries); }
    public BatchInsertResult addPaylogsBatchDetailed(Collection<TransactionEntry> entries) { return coordinator.addPaylogsBatchDetailed(entries); }
    public List<TransactionEntry> queryPaylogs(String player, int direction, String query, int limit, int offset) { return coordinator.queryPaylogs(player, direction, query, limit, offset); }
    public QueryPage<TransactionEntry> queryPaylogPage(String player, int direction, String query, int limit, int offset) { return coordinator.queryPaylogPage(player, direction, query, limit, offset); }
    public QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, int limit, int offset) { return coordinator.queryDealHistoryPage(player, query, limit, offset); }
    public QueryPage<TransactionEntry> queryAvailablePaylogs(String payer, String receiver, String query, int limit, int offset) { return coordinator.queryAvailablePaylogs(payer, receiver, query, limit, offset); }
    public QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, boolean includeArchived, DealHistorySort sort, int limit, int offset) { return coordinator.queryDealHistoryPage(player, query, includeArchived, sort, limit, offset); }
    public List<CreditEntry> queryDealHistory(String player, String query, int limit, int offset) { return coordinator.queryDealHistory(player, query, limit, offset); }
    public List<DataHealthRecord> runHealthCheck() { return coordinator.runHealthCheck(); }
    public List<DataHealthRecord> listHealthRecords(boolean includeResolved) { return coordinator.listHealthRecords(includeResolved); }
    public boolean resolveHealthRecord(UUID id, String repairPayload, boolean ignored) { return coordinator.resolveHealthRecord(id, repairPayload, ignored); }

    public record DatabaseState(List<CreditEntry> credits, List<Payment> payments, List<CreditEventEntry> events) { }
    public record BatchInsertResult(int requested, int inserted, int skipped, int failed, List<String> warnings) { }
    public enum DatabaseAvailability { UNKNOWN, HEALTHY, SCHEMA_REPAIRABLE, PHYSICALLY_CORRUPT, RESTORED_FROM_BACKUP, NEEDS_USER_RECOVERY, EMPTY_BUT_BACKUP_EXISTS, WRITE_LOCKED }
    public record CreditMutation(CreditEntry credit, List<Payment> paymentUpserts, List<UUID> paymentDeletions, List<CreditEventEntry> events) { }
    public record BackupManifestEntry(String fileName, long createdAt, int schemaVersion, long revision, int creditCount, int paymentCount, int paylogCount, int eventCount, boolean healthy, String format) {
        public int domainCount() { return creditCount + paymentCount + paylogCount + eventCount; }
    }
    public record LegacyRecord(String kind, String originalId, String rawPayload, String reason) { }
    public record AutomaticMigrationResult(boolean success, int credits, int payments, int events, int paylogs, int preservedRecords) {
        static AutomaticMigrationResult failed() { return new AutomaticMigrationResult(false, 0, 0, 0, 0, 0); }
    }
    public record QueryPage<T>(List<T> entries, long totalCount, int offset, int pageSize) {
        public boolean hasPrevious() { return offset > 0; }
        public boolean hasNext() { return offset + entries.size() < totalCount; }
        public int pageNumber() { return offset / pageSize + 1; }
        public int pageCount() { return Math.max(1, (int) Math.ceil(totalCount / (double) pageSize)); }
    }
    public enum DealHistorySort { NEWEST, OLDEST, AMOUNT_DESC, AMOUNT_ASC, PLAYER_ASC, STATUS }
    public record DataHealthRecord(UUID id, String type, String severity, String sourceTable, String sourceId, String title, String message, String rawPayload, String repairPayload, String status, long createdAt, Long resolvedAt) { }
}
