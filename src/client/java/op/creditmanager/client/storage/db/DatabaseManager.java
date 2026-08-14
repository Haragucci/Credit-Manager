package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.FileManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class DatabaseManager {
    public static final int SCHEMA_VERSION = DatabaseCoordinator.SCHEMA_VERSION;
    public static final int PAGE_SIZE = DatabaseCoordinator.PAGE_SIZE;
    private static final DatabaseManager INSTANCE = new DatabaseManager();
    private final DatabaseCoordinator coordinator;
    private BackupCheckpointService checkpointService;
    private Path checkpointRoot;

    private DatabaseManager() { this(new DatabaseCoordinator()); }
    DatabaseManager(DatabaseCoordinator coordinator) { this.coordinator = coordinator; }

    public static DatabaseManager getInstance() { return INSTANCE; }
    public DatabaseAvailability availability() { return coordinator.availability(); }
    public boolean requiresUserRecovery() { return coordinator.requiresUserRecovery(); }
    public void initialize() { coordinator.initialize(); ensureCheckpointService(); }
    public boolean createEmptyDatabaseAfterPhysicalRecovery() { boolean result = coordinator.createEmptyDatabaseAfterPhysicalRecovery(); if (result) ensureCheckpointService(); return result; }
    public boolean isHealthy() { return coordinator.isHealthy(); }
    public boolean isWriteLocked() { return coordinator.isWriteLocked(); }
    public boolean isSafeForWrites() { return coordinator.isSafeForWrites(); }
    public boolean recheckAndRepair() { boolean result = coordinator.recheckAndRepair(); if (result) ensureCheckpointService(); return result; }
    public long revision() { return coordinator.revision(); }
    public boolean hasDomainData() { return coordinator.hasDomainData(); }
    public boolean hasCompletedJsonMigration() { return coordinator.hasCompletedJsonMigration(); }
    public boolean hasCompletedAutomaticJsonMigration() { return coordinator.hasCompletedAutomaticJsonMigration(); }
    public boolean hasPendingAutomaticJsonMigration() { return coordinator.hasPendingAutomaticJsonMigration(); }
    public boolean createBackup() { return coordinator.createBackup(); }
    public ManualBackupResult createHealthyBackupNow() {
        ManualBackupResult result = coordinator.createHealthyBackupNow();
        if (result.localSuccess()) {
            ensureCheckpointService();
            if (checkpointService != null) checkpointService.acceptExternalResult(BackupCheckpointService.CheckpointResult.of(
                    true, result.mirrorSuccess(), FileManager.getBackupMirrorDirectory() != null,
                    result.revision(), System.currentTimeMillis()));
        }
        return result;
    }
    public boolean createRecoverySnapshot() { return coordinator.createRecoverySnapshot(); }
    public DatabaseState loadCreditState() { return coordinator.loadCreditState(); }
    public DatabaseState loadRuntimeCreditState() { return coordinator.loadRuntimeCreditState(); }
    public boolean replaceCreditState(Collection<CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) { boolean result = coordinator.replaceCreditState(credits, payments, events); checkpoint(result); return result; }
    public boolean replaceCreditDataPreservingEvents(Collection<CreditEntry> credits, Collection<Payment> payments) { boolean result = coordinator.replaceCreditDataPreservingEvents(credits, payments); checkpoint(result); return result; }
    public boolean commitCreditMutation(CreditMutation mutation) { boolean result = coordinator.commitCreditMutation(mutation); checkpoint(result); return result; }
    public MutationCommitReceipt commitCreditMutationWithReceipt(CreditMutation mutation) { MutationCommitReceipt result = coordinator.commitCreditMutationWithReceipt(mutation); if (result.committed()) requestCheckpoint(result.revision()); return result; }
    public boolean commitCreditMutationsBatch(Collection<CreditMutation> mutations) { boolean result = coordinator.commitCreditMutationsBatch(mutations); checkpoint(result); return result; }
    public void markRuntimeStateDegraded(String reason) { coordinator.markRuntimeStateDegraded(reason); }
    public boolean importLegacy(DatabaseState state, Collection<TransactionEntry> paylogs, String details) { boolean result = coordinator.importLegacy(state, paylogs, details); checkpoint(result); return result; }
    public AutomaticMigrationResult importLegacyAutomatically(DatabaseState state, Collection<TransactionEntry> paylogs, Collection<LegacyRecord> preserved, String details) { AutomaticMigrationResult result = coordinator.importLegacyAutomatically(state, paylogs, preserved, details); checkpoint(result.success()); return result; }
    public boolean completeAutomaticJsonMigration(String details) { boolean result = coordinator.completeAutomaticJsonMigration(details); checkpoint(result); return result; }
    public int legacyRecordCount() { return coordinator.legacyRecordCount(); }
    public boolean addPaylog(TransactionEntry entry) { boolean result = coordinator.addPaylog(entry); checkpoint(result); return result; }
    public boolean resetCorruptDatabaseWithBackup() { boolean result = coordinator.resetCorruptDatabaseWithBackup(); if (result) ensureCheckpointService(); return result; }
    public boolean restoreLatestValidBackup() { boolean result = coordinator.restoreLatestValidBackup(); if (result) ensureCheckpointService(); return result; }
    public List<BackupManifestEntry> listBackups() { return coordinator.listBackups(); }
    public List<AvailableBackup> listAvailableBackups() { return coordinator.listAvailableBackups(); }
    public Optional<TransactionEntry> findPaylog(UUID id) { return coordinator.findPaylog(id); }
    public int addPaylogsBatch(Collection<TransactionEntry> entries) { int result = coordinator.addPaylogsBatch(entries); checkpoint(result > 0); return result; }
    public BatchInsertResult addPaylogsBatchDetailed(Collection<TransactionEntry> entries) { BatchInsertResult result = coordinator.addPaylogsBatchDetailed(entries); checkpoint(result.inserted() > 0); return result; }
    public List<TransactionEntry> queryPaylogs(String player, int direction, String query, int limit, int offset) { return coordinator.queryPaylogs(player, direction, query, limit, offset); }
    public QueryPage<TransactionEntry> queryPaylogPage(String player, int direction, String query, int limit, int offset) { return coordinator.queryPaylogPage(player, direction, query, limit, offset); }
    public QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, int limit, int offset) { return coordinator.queryDealHistoryPage(player, query, limit, offset); }
    public QueryPage<TransactionEntry> queryAvailablePaylogs(String payer, String receiver, String query, int limit, int offset) { return coordinator.queryAvailablePaylogs(payer, receiver, query, limit, offset); }
    public QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, boolean includeArchived, DealHistorySort sort, int limit, int offset) { return coordinator.queryDealHistoryPage(player, query, includeArchived, sort, limit, offset); }
    public List<CreditEntry> queryDealHistory(String player, String query, int limit, int offset) { return coordinator.queryDealHistory(player, query, limit, offset); }
    public QueryPage<CreditEventEntry> queryCreditEventPage(String player, UUID creditId, int limit, int offset) { return coordinator.queryCreditEventPage(player, creditId, limit, offset); }
    public StatisticsEventSlice queryStatisticsEvents(String player, long fromInclusive, long toInclusive) { return coordinator.queryStatisticsEvents(player, fromInclusive, toInclusive); }
    public List<DataHealthRecord> runHealthCheck() { return coordinator.runHealthCheck(); }
    public List<DataHealthRecord> listHealthRecords(boolean includeResolved) { return coordinator.listHealthRecords(includeResolved); }
    public boolean resolveHealthRecord(UUID id, String repairPayload, boolean ignored) { boolean result = coordinator.resolveHealthRecord(id, repairPayload, ignored); checkpoint(result); return result; }
    public boolean repairCredit(CreditEntry replacement, String reason) { boolean result = coordinator.repairCredit(replacement, reason); checkpoint(result); return result; }
    public boolean repairPayment(Payment replacement, String reason) { boolean result = coordinator.repairPayment(replacement, reason); checkpoint(result); return result; }
    public boolean repairEvent(CreditEventEntry replacement, String reason) { boolean result = coordinator.repairEvent(replacement, reason); checkpoint(result); return result; }
    public boolean discardRecoveryRecord(DiscardRecordType type, UUID id, String reason, boolean confirmed) { boolean result = coordinator.discardRecoveryRecord(type, id, reason, confirmed); checkpoint(result); return result; }

    public synchronized BackupProtectionMetrics backupProtectionMetrics() {
        if (checkpointService == null) return new BackupProtectionMetrics(0L, -1L, 0L, 0L, 0, false, true,
                -1L, -1L, 0L, 0L, BackupProtectionState.DEGRADED);
        BackupCheckpointService.Metrics metrics = checkpointService.metrics();
        return new BackupProtectionMetrics(metrics.currentRevision(), metrics.latestHealthyBackupRevision(), metrics.backupLagRevisions(),
                metrics.latestHealthyBackupAt(), metrics.consecutiveBackupFailures(), metrics.checkpointPending(), metrics.protectionDegraded(),
                metrics.latestLocalBackupRevision(), metrics.latestMirrorBackupRevision(), metrics.latestLocalBackupAt(),
                metrics.latestMirrorBackupAt(), BackupProtectionState.valueOf(metrics.protectionState().name()));
    }

    public synchronized boolean shutdown() {
        return shutdown(Duration.ofSeconds(5));
    }

    public synchronized boolean shutdown(Duration timeout) {
        boolean flushed = true;
        if (checkpointService != null) {
            flushed = checkpointService.flushAndShutdown(timeout);
            if (flushed) {
                checkpointService = null;
                checkpointRoot = null;
            }
        }
        if (flushed) FileManager.shutdown();
        return flushed;
    }

    private void checkpoint(boolean committed) {
        if (committed) requestCheckpoint(coordinator.revision());
    }

    private synchronized void requestCheckpoint(long revision) {
        ensureCheckpointService();
        if (checkpointService != null) checkpointService.request(revision);
    }

    private synchronized void ensureCheckpointService() {
        if (FileManager.storageAccessState() != FileManager.StorageAccessState.PRIMARY || !coordinator.isHealthy()) return;
        Path currentRoot = FileManager.getDataDirectory().toAbsolutePath().normalize();
        if (checkpointService != null && currentRoot.equals(checkpointRoot)) return;
        if (checkpointService != null) checkpointService.stopNow();
        checkpointRoot = currentRoot;
        checkpointService = new BackupCheckpointService(this::createAutomaticCheckpoint, coordinator::updateBackupProtection);
        long currentRevision = coordinator.revision();
        List<AvailableBackup> backups = coordinator.listAvailableBackups();
        long localRevision = -1L;
        long mirrorRevision = -1L;
        long localCreatedAt = 0L;
        long mirrorCreatedAt = 0L;
        for (AvailableBackup backup : backups) {
            if (!backup.entry().automaticRestoreEligible()) continue;
            if (backup.source() == BackupSource.LOCAL || backup.source() == BackupSource.LOCAL_AND_MIRROR) {
                if (backup.entry().revision() > localRevision) {
                    localRevision = backup.entry().revision();
                    localCreatedAt = backup.entry().createdAt();
                }
            }
            if (backup.source() == BackupSource.MIRROR || backup.source() == BackupSource.LOCAL_AND_MIRROR) {
                if (backup.entry().revision() > mirrorRevision) {
                    mirrorRevision = backup.entry().revision();
                    mirrorCreatedAt = backup.entry().createdAt();
                }
            }
        }
        checkpointService.seed(currentRevision, localRevision, localCreatedAt, mirrorRevision, mirrorCreatedAt,
                FileManager.getBackupMirrorDirectory() != null);
        checkpointService.request(currentRevision);
    }

    private BackupCheckpointService.CheckpointResult createAutomaticCheckpoint() {
        return coordinator.createBackupCheckpoint();
    }

    public record DatabaseState(List<CreditEntry> credits, List<Payment> payments, List<CreditEventEntry> events) { }
    public record BatchInsertResult(int requested, int inserted, int skipped, int failed, List<String> warnings) { }
    public record BackupProtectionMetrics(long currentRevision, long latestHealthyBackupRevision, long backupLagRevisions,
                                          long latestHealthyBackupAt, int consecutiveBackupFailures,
                                          boolean checkpointPending, boolean protectionDegraded,
                                          long latestLocalBackupRevision, long latestMirrorBackupRevision,
                                          long latestLocalBackupAt, long latestMirrorBackupAt,
                                          BackupProtectionState protectionState) { }
    public record ManualBackupResult(boolean localSuccess, boolean mirrorSuccess, long revision,
                                     Path localArtifact, Path mirrorArtifact, String message) { }
    public enum DatabaseAvailability {
        UNKNOWN,
        HEALTHY,
        SECONDARY_INSTANCE,
        STORAGE_LOCATION_UNRESOLVED,
        MISSING_DATABASE,
        STORAGE_IDENTITY_MISMATCH,
        STORAGE_CONFLICT,
        SCHEMA_REPAIRABLE,
        PHYSICALLY_CORRUPT,
        RESTORED_FROM_BACKUP,
        NEEDS_USER_RECOVERY,
        EMPTY_BUT_BACKUP_EXISTS,
        BACKUP_PROTECTION_DEGRADED,
        BACKUP_PROTECTION_CRITICAL,
        WRITE_LOCKED
    }
    public record CreditMutation(CreditEntry credit, List<Payment> paymentUpserts, List<UUID> paymentDeletions, List<CreditEventEntry> events) { }
    public record MutationCommitReceipt(boolean committed, long revision, boolean verified) {
        public static MutationCommitReceipt notCommitted() { return new MutationCommitReceipt(false, -1L, false); }
        public static MutationCommitReceipt committed(long revision) { return new MutationCommitReceipt(true, revision, true); }
        public static MutationCommitReceipt committedUnverified(long revision) { return new MutationCommitReceipt(true, revision, false); }
    }
    public record BackupManifestEntry(String fileName, long createdAt, int schemaVersion, long revision, int creditCount,
                                      int paymentCount, int paylogCount, int eventCount, boolean healthy, String format,
                                      String sha256, long size, int archiveVersion, BackupArtifactType artifactType,
                                      boolean automaticRestoreEligible) {
        public BackupManifestEntry {
            boolean legacyArtifact = artifactType == null;
            artifactType = legacyArtifact ? healthy ? BackupArtifactType.RESTORABLE_HEALTHY : BackupArtifactType.RECOVERY_SNAPSHOT : artifactType;
            automaticRestoreEligible = artifactType == BackupArtifactType.RESTORABLE_HEALTHY && healthy
                    && (automaticRestoreEligible || legacyArtifact);
        }

        public BackupManifestEntry(String fileName, long createdAt, int schemaVersion, long revision, int creditCount,
                                   int paymentCount, int paylogCount, int eventCount, boolean healthy, String format,
                                   String sha256, long size, int archiveVersion) {
            this(fileName, createdAt, schemaVersion, revision, creditCount, paymentCount, paylogCount, eventCount,
                    healthy, format, sha256, size, archiveVersion,
                    healthy ? BackupArtifactType.RESTORABLE_HEALTHY : BackupArtifactType.RECOVERY_SNAPSHOT, healthy);
        }

        public BackupManifestEntry(String fileName, long createdAt, int schemaVersion, long revision, int creditCount, int paymentCount, int paylogCount, int eventCount, boolean healthy, String format) {
            this(fileName, createdAt, schemaVersion, revision, creditCount, paymentCount, paylogCount, eventCount,
                    healthy, format, null, 0L, 1);
        }
        public int domainCount() { return creditCount + paymentCount + paylogCount + eventCount; }
    }
    public enum BackupArtifactType { RESTORABLE_HEALTHY, RECOVERY_SNAPSHOT }
    public enum BackupProtectionState { HEALTHY, LOCAL_ONLY, MIRROR_ONLY, DEGRADED, CRITICAL }
    public enum BackupSource { LOCAL, MIRROR, LOCAL_AND_MIRROR }
    public record AvailableBackup(BackupManifestEntry entry, BackupSource source) { }
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
    public record StatisticsEventSlice(List<CreditEventEntry> events, Set<UUID> inactiveCreditIds) { }
    public enum DealHistorySort { NEWEST, OLDEST, AMOUNT_DESC, AMOUNT_ASC, PLAYER_ASC, STATUS }
    public enum DiscardRecordType { CREDIT, PAYMENT, EVENT }
    public record DataHealthRecord(UUID id, String type, String severity, String sourceTable, String sourceId, String title, String message, String rawPayload, String repairPayload, String status, long createdAt, Long resolvedAt) { }
}
