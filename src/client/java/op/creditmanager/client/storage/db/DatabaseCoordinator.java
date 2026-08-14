package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.validation.CreditValidationRules;
import op.creditmanager.client.money.CreditStatusRules;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.money.MoneyAggregate;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.PaymentKind;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.search.DealSearchText;
import op.creditmanager.client.search.PaylogSearchText;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.dao.DatabaseMetadataDao;
import op.creditmanager.client.storage.db.dao.PaylogSearchTokenDao;
import op.creditmanager.client.storage.db.schema.DatabaseSchemaManager;
import op.creditmanager.client.storage.db.DatabaseManager.AutomaticMigrationResult;
import op.creditmanager.client.storage.db.DatabaseManager.AvailableBackup;
import op.creditmanager.client.storage.db.DatabaseManager.BackupArtifactType;
import op.creditmanager.client.storage.db.DatabaseManager.BackupManifestEntry;
import op.creditmanager.client.storage.db.DatabaseManager.BackupSource;
import op.creditmanager.client.storage.db.DatabaseManager.BatchInsertResult;
import op.creditmanager.client.storage.db.DatabaseManager.CreditMutation;
import op.creditmanager.client.storage.db.DatabaseManager.DataHealthRecord;
import op.creditmanager.client.storage.db.DatabaseManager.DatabaseAvailability;
import op.creditmanager.client.storage.db.DatabaseManager.DatabaseState;
import op.creditmanager.client.storage.db.DatabaseManager.DealHistorySort;
import op.creditmanager.client.storage.db.DatabaseManager.DiscardRecordType;
import op.creditmanager.client.storage.db.DatabaseManager.LegacyRecord;
import op.creditmanager.client.storage.db.DatabaseManager.ManualBackupResult;
import op.creditmanager.client.storage.db.DatabaseManager.MutationCommitReceipt;
import op.creditmanager.client.storage.db.DatabaseManager.QueryPage;
import op.creditmanager.client.storage.db.DatabaseManager.StatisticsEventSlice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DatabaseCoordinator {
    public static final int SCHEMA_VERSION = 8;
    public static final int PAGE_SIZE = 500;
    private static final int ID_BATCH_SIZE = 500;
    private static final int MAX_ACTIVE_BACKUPS = 32;
    private static final int MAX_RETIRED_BACKUPS = 64;
    private static final long MAX_RETIRED_BACKUP_BYTES = 8_589_934_592L;
    private static final String BACKUP_PROTECTION_DEGRADED_REASON = "Automatische Backup-Checkpoints sind wiederholt fehlgeschlagen; der Backup-Schutz ist degradiert.";
    private static final String BACKUP_PROTECTION_CRITICAL_REASON = "Kein ausreichend aktueller validierter Recovery Point ist verfügbar; normale Änderungen sind vorübergehend gesperrt.";
    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST = new TypeToken<List<String>>() { }.getType();
    private static final Type BACKUP_MANIFEST_LIST = new TypeToken<List<BackupManifestEntry>>() { }.getType();
    private volatile boolean initialized;
    private volatile boolean healthy = true;
    private volatile boolean writeLocked;
    private volatile boolean recovering;
    private volatile BackupCheckpointService.ProtectionState backupProtectionState = BackupCheckpointService.ProtectionState.HEALTHY;
    private boolean explicitFreshBootstrap;
    private volatile Path initializedAt;
    private volatile DatabaseAvailability availability = DatabaseAvailability.UNKNOWN;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final ReentrantLock writerLock = new ReentrantLock(true);
    private final DatabaseFaultInjector faultInjector;
    private final RecoveryFileOps recoveryFileOps;
    private final DatabaseConnectionFactory connections = new DatabaseConnectionFactory();
    private final DatabaseMetadataDao metadataDao = new DatabaseMetadataDao();
    private final FreshDatabasePolicy freshDatabasePolicy = new FreshDatabasePolicy();
    private final PaylogSearchTokenDao paylogSearchTokens = new PaylogSearchTokenDao();
    private final DatabaseWriteGate writeGate = new DatabaseWriteGate();
    private final DatabaseHealthInspectionService healthInspectionService = new DatabaseHealthInspectionService(this);
    private final DatabaseSchemaManager schemaManager = new DatabaseSchemaManager(metadataDao, paylogSearchTokens, healthInspectionService::storeHealth);
    private final DatabaseMigrationService migrationService;
    private final StorageIdentityGuard storageIdentityGuard;
    private final DatabaseQueryService queryService = new DatabaseQueryService(this);
    private final CreditStateReplacementService creditStateReplacementService = new CreditStateReplacementService(this);
    private final DatabaseLogicalRepairService logicalRepairService = new DatabaseLogicalRepairService(this);

    DatabaseCoordinator() { this(DatabaseFaultInjector.NONE, new RecoveryFileOps()); }
    DatabaseCoordinator(DatabaseFaultInjector faultInjector) { this(faultInjector, new RecoveryFileOps()); }
    DatabaseCoordinator(DatabaseFaultInjector faultInjector, RecoveryFileOps recoveryFileOps) {
        this.faultInjector = faultInjector == null ? DatabaseFaultInjector.NONE : faultInjector;
        this.recoveryFileOps = recoveryFileOps == null ? new RecoveryFileOps() : recoveryFileOps;
        this.migrationService = new DatabaseMigrationService(connections, schemaManager, this.recoveryFileOps);
        this.storageIdentityGuard = new StorageIdentityGuard(metadataDao, this.recoveryFileOps);
    }
    public synchronized DatabaseAvailability availability() { return availability; }
    public synchronized boolean requiresUserRecovery() { return availability == DatabaseAvailability.PHYSICALLY_CORRUPT || availability == DatabaseAvailability.NEEDS_USER_RECOVERY; }

    public synchronized void initialize() {
        FileManager.initialize();
        Path requestedPath = FileManager.getDatabaseFile().toAbsolutePath();
        if (initialized && requestedPath.equals(initializedAt) && availability != DatabaseAvailability.UNKNOWN) return;
        lifecycleLock.writeLock().lock();
        try {
            if (initialized && requestedPath.equals(initializedAt) && availability != DatabaseAvailability.UNKNOWN) return;
            initialized = false;
            healthy = true;
            writeLocked = false;
            availability = DatabaseAvailability.UNKNOWN;
            if (!FileManager.databaseAccessAllowed()) {
                DatabaseAvailability blocked = switch (FileManager.storageAccessState()) {
                    case SECONDARY_INSTANCE -> DatabaseAvailability.SECONDARY_INSTANCE;
                    case STORAGE_LOCATION_UNRESOLVED -> DatabaseAvailability.STORAGE_LOCATION_UNRESOLVED;
                    default -> DatabaseAvailability.WRITE_LOCKED;
                };
                String reason = blocked == DatabaseAvailability.SECONDARY_INSTANCE
                        ? "CreditManager läuft bereits in einer anderen Minecraft-Instanz. Diese Instanz greift zum Schutz der Daten nicht auf die Datenbank zu."
                        : "Der persistente CreditManager-Datenpfad konnte nicht sicher bestimmt oder geöffnet werden.";
                markUnavailable(blocked, reason, requestedPath);
                return;
            }
            try {
            connections.loadDriver();
            if (!migrateLegacyOverlayStorage()) return;
            migrationService.recoverInterruptedMigration();
            boolean existingDatabase = Files.isRegularFile(FileManager.getDatabaseStorageFile());
            int installed = 0;
            if (existingDatabase) {
                try (Connection connection = connections.openExistingReadOnly(requestedPath)) {
                    faultInjector.hit(DatabaseFaultInjector.FailurePoint.STARTUP_AFTER_OPEN_BEFORE_SCHEMA_VALIDATION);
                    schemaManager.validateMetadataTables(connection);
                    if (!schemaManager.hasAnyCoreTable(connection)) {
                        throw new DatabaseSchemaManager.SchemaValidationException("Existing database contains no complete CreditManager core schema");
                    }
                    installed = schemaManager.installedSchemaVersion(connection);
                }
                if (installed > SCHEMA_VERSION) {
                    writeLocked = true;
                    healthy = false;
                    availability = DatabaseAvailability.WRITE_LOCKED;
                    CreditManagerClient.LOGGER.error("CreditManager database schema {} is newer than supported {}. Writes are locked.", installed, SCHEMA_VERSION);
                } else if (installed < SCHEMA_VERSION) {
                    migrationService.migrateLegacyToV8(requestedPath, installed);
                }
            }
            if (!writeLocked) {
                StorageIdentityGuard.StorageIdentity freshIdentity = null;
                if (!existingDatabase) {
                    long generation = 1L;
                    if (explicitFreshBootstrap) {
                        generation = archiveIdentityForExplicitFreshBootstrap();
                    } else {
                        FreshDatabasePolicy.Decision decision = freshDatabasePolicy.inspect(FileManager.getDataDirectory(),
                                FileManager.getLegacyDataDirectory(), FileManager.getBackupMirrorDirectory(),
                                FileManager.hasPrimaryStorageLease());
                        if (!decision.allowsBootstrap()) {
                            markUnavailable(DatabaseAvailability.MISSING_DATABASE,
                                    "Die frühere Datenbank fehlt, während vorhandene Storage-Artefakte auf bestehende Nutzerdaten hinweisen. Es wurde keine neue Datenbank erstellt.", requestedPath);
                            return;
                        }
                    }
                    freshIdentity = storageIdentityGuard.newIdentity(storageInstanceKey(), generation);
                }
                try (Connection connection = existingDatabase
                        ? connections.openExistingReadWrite(requestedPath) : connections.createFresh(requestedPath)) {
                    faultInjector.hit(DatabaseFaultInjector.FailurePoint.STARTUP_AFTER_OPEN_BEFORE_SCHEMA_VALIDATION);
                    connection.setAutoCommit(false);
                    try {
                        if (!existingDatabase) schemaManager.createFreshSchema(connection);
                        schemaManager.ensureAdditiveSchemaObjects(connection);
                        schemaManager.validateRequiredSchema(connection);
                        schemaManager.backfill(connection);
                        schemaManager.validateRequiredSchema(connection);
                        if (freshIdentity != null) storageIdentityGuard.writeDatabaseIdentity(connection, freshIdentity);
                        connection.commit();
                    } catch (Exception error) {
                        connection.rollback();
                        throw error;
                    }
                }
                if (freshIdentity != null) storageIdentityGuard.writeSidecar(FileManager.getStorageIdentityFile(), freshIdentity);
                ensureStorageIdentity(requestedPath);
            }
            initialized = true;
            initializedAt = requestedPath;
            if (activeDatabaseIsUnexpectedlyEmpty()) {
                writeLocked = true;
                healthy = false;
                availability = DatabaseAvailability.EMPTY_BUT_BACKUP_EXISTS;
                DataHealth.reportRecoveryRequired("Aktive Datenbank ist leer, obwohl ein Backup mit CreditManager-Daten vorhanden ist.");
                CreditManagerClient.LOGGER.error("CreditManager database is empty while a backup manifest reports domain data. Writes are locked.");
            } else if (healthy) {
                availability = DatabaseAvailability.HEALTHY;
            }
            } catch (StorageIdentityGuard.StorageIdentityException exception) {
            markUnavailable(DatabaseAvailability.STORAGE_IDENTITY_MISMATCH,
                    "Storage-Identität von Datenbank und Sidecar stimmt nicht überein. Kein Datenbestand wurde überschrieben.", requestedPath);
            CreditManagerClient.LOGGER.error("CreditManager storage identity validation failed; writes are locked.", exception);
            } catch (DatabaseSchemaManager.SchemaValidationException exception) {
            healthy = false;
            writeLocked = true;
            availability = DatabaseAvailability.SCHEMA_REPAIRABLE;
            DataHealth.reportRecoveryRequired("Datenbank-Schema konnte nicht sicher repariert werden. Keine Daten wurden gelöscht.");
            CreditManagerClient.LOGGER.error("CreditManager database schema validation failed; writes are locked.", exception);
            initialized = true;
            initializedAt = requestedPath;
            } catch (Exception exception) {
            if (isMissingDatabase(exception)) {
                markUnavailable(DatabaseAvailability.MISSING_DATABASE,
                        "Die aktive CreditManager-Datenbank fehlt. Es wurde keine neue leere Datenbank erzeugt.", requestedPath);
                CreditManagerClient.LOGGER.error("CreditManager database disappeared; automatic creation was blocked.", exception);
                return;
            }
            if (isPhysicalH2Corruption(exception)) {
                handlePhysicalCorruption(exception, requestedPath);
                return;
            }
            healthy = false;
            writeLocked = true;
            availability = DatabaseAvailability.WRITE_LOCKED;
            DataHealth.reportRecoveryRequired("Lokale Datenbank konnte nicht gelesen werden. Keine Daten wurden gelöscht.");
            CreditManagerClient.LOGGER.error("Could not initialise the local CreditManager database", exception);
            initialized = true;
            initializedAt = requestedPath;
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void markUnavailable(DatabaseAvailability state, String reason, Path requestedPath) {
        healthy = false;
        writeLocked = true;
        availability = state;
        initialized = true;
        initializedAt = requestedPath;
        DataHealth.reportRecoveryRequired(reason);
    }

    private String storageInstanceKey() {
        return FileManager.storageLocation() == null ? "unknown" : FileManager.storageLocation().instanceKey();
    }

    private BackupMirrorService mirrorService() {
        return new BackupMirrorService(FileManager.getBackupMirrorDirectory(), recoveryFileOps);
    }

    private boolean migrateLegacyOverlayStorage() throws Exception {
        Path legacyRoot = FileManager.getLegacyDataDirectory();
        Path canonicalRoot = FileManager.getDataDirectory().toAbsolutePath().normalize();
        if (legacyRoot == null) return true;
        legacyRoot = legacyRoot.toAbsolutePath().normalize();
        if (legacyRoot.equals(canonicalRoot) || !Files.isDirectory(legacyRoot)) return true;
        Path legacyStorage = legacyRoot.resolve("creditmanager.mv.db");
        Path canonicalStorage = FileManager.getDatabaseStorageFile();
        if (Files.isRegularFile(legacyStorage) && Files.isRegularFile(canonicalStorage)) {
            if (!sameDatabaseHistory(legacyStorage, canonicalStorage)) {
                markUnavailable(DatabaseAvailability.STORAGE_CONFLICT,
                        "Legacy-Overlay und persistenter Speicher enthalten unterschiedliche Datenstände. Es wurde nichts überschrieben.",
                        FileManager.getDatabaseFile().toAbsolutePath());
                return false;
            }
        } else if (Files.isRegularFile(legacyStorage)) {
            Path migrationRoot = FileManager.getRecoveryDirectory().resolve("legacy-overlay-migration-" + System.currentTimeMillis() + '-' + UUID.randomUUID());
            recoveryFileOps.createDirectories(migrationRoot);
            Path archive = migrationRoot.resolve("legacy-overlay-backup.zip");
            Path legacyBase = legacyRoot.resolve("creditmanager");
            try (Connection connection = connections.openExistingReadOnly(legacyBase); Statement statement = connection.createStatement()) {
                schemaManager.validateMetadataTables(connection);
                if (!schemaManager.hasAnyCoreTable(connection)) throw new IOException("Legacy overlay database has no CreditManager schema");
                statement.execute("BACKUP TO '" + escapeSqlLiteral(archive.toAbsolutePath().normalize().toString()) + "'");
            }
            BackupValidation validation = validateBackup(archive);
            if (!validation.valid() || !DatabaseSchemaSupportPolicy.supports(validation.schemaVersion()) || !restoreBackupArchive(archive)) {
                markUnavailable(DatabaseAvailability.NEEDS_USER_RECOVERY,
                        "Legacy-Overlay-Daten konnten nicht vollständig validiert und installiert werden. Die Quelle blieb unverändert.",
                        FileManager.getDatabaseFile().toAbsolutePath());
                return false;
            }
        }
        try {
            copyLegacyNonDatabaseArtifacts(legacyRoot, canonicalRoot);
        } catch (IOException exception) {
            markUnavailable(DatabaseAvailability.STORAGE_CONFLICT,
                    "Legacy-Overlay-Artefakte konnten nicht vollständig in den persistenten Speicher übernommen werden. Die Quelle blieb unverändert.",
                    FileManager.getDatabaseFile().toAbsolutePath());
            CreditManagerClient.LOGGER.error("CreditManager legacy overlay artifact migration failed", exception);
            return false;
        }
        return true;
    }

    private boolean sameDatabaseHistory(Path leftStorage, Path rightStorage) {
        try {
            if (Objects.equals(sha256File(leftStorage), sha256File(rightStorage))) return true;
            DatabaseFingerprint left = databaseFingerprint(leftStorage);
            DatabaseFingerprint right = databaseFingerprint(rightStorage);
            return left.storageUuid() != null && left.storageUuid().equals(right.storageUuid())
                    && left.schemaVersion() == right.schemaVersion() && left.revision() == right.revision()
                    && java.util.Arrays.equals(left.domainCounts(), right.domainCounts());
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not compare canonical and legacy CreditManager databases safely", exception);
            return false;
        }
    }

    private DatabaseFingerprint databaseFingerprint(Path storage) throws Exception {
        String fileName = storage.getFileName().toString();
        Path base = storage.resolveSibling(fileName.substring(0, fileName.length() - ".mv.db".length()));
        try (Connection connection = connections.openExistingReadOnly(base); Statement statement = connection.createStatement()) {
            schemaManager.validateMetadataTables(connection);
            int version = schemaManager.installedSchemaVersion(connection);
            String uuid = metadataDao.read(connection, "storage_uuid");
            long revision = metadataDao.revision(connection);
            long[] counts = new long[4];
            String[] tables = {"credits", "payments", "credit_events", "paylogs"};
            for (int index = 0; index < tables.length; index++) {
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tables[index])) {
                    result.next();
                    counts[index] = result.getLong(1);
                }
            }
            return new DatabaseFingerprint(uuid, version, revision, counts);
        }
    }

    private void copyLegacyNonDatabaseArtifacts(Path sourceRoot, Path targetRoot) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                if (Files.isSymbolicLink(source)) throw new IOException("Symbolic links are not allowed in legacy storage migration");
                if (source.equals(sourceRoot) || Files.isDirectory(source)) continue;
                String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals("creditmanager.mv.db") || name.equals("creditmanager.trace.db")
                        || name.equals("creditmanager.lock.db") || name.equals(".creditmanager.lock") || name.endsWith(".tmp")) continue;
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot)) throw new IOException("Unsafe legacy storage path");
                if (Files.exists(target)) continue;
                recoveryFileOps.createDirectories(target.getParent());
                Path temporary = target.resolveSibling(target.getFileName() + ".legacy-migration.tmp");
                recoveryFileOps.copy(source, temporary, StandardCopyOption.COPY_ATTRIBUTES);
                try {
                    recoveryFileOps.moveWithoutReplacing(temporary, target);
                } catch (IOException exception) {
                    recoveryFileOps.deleteIfExists(temporary);
                    throw exception;
                }
            }
        }
    }

    private void ensureStorageIdentity(Path databaseBase) throws Exception {
        StorageIdentityGuard.StorageIdentity sidecarToWrite = null;
        try (Connection connection = connections.openExistingReadWrite(databaseBase)) {
            StorageIdentityGuard.Inspection inspection = storageIdentityGuard.inspect(connection, FileManager.getStorageIdentityFile());
            switch (inspection.status()) {
                case MATCH -> { return; }
                case MISMATCH, DATABASE_IDENTITY_MISSING -> throw new StorageIdentityGuard.StorageIdentityException("Storage identity mismatch");
                case SIDECAR_MISSING -> sidecarToWrite = inspection.databaseIdentity();
                case MISSING_BOTH -> {
                    if (hasDomainData(connection)) createAndValidateIdentitySafetyBackup(connection);
                    StorageIdentityGuard.StorageIdentity identity = storageIdentityGuard.newIdentity(storageInstanceKey(), 1L);
                    connection.setAutoCommit(false);
                    try {
                        storageIdentityGuard.writeDatabaseIdentity(connection, identity);
                        connection.commit();
                    } catch (Exception exception) {
                        connection.rollback();
                        throw exception;
                    }
                    sidecarToWrite = identity;
                }
            }
        }
        if (sidecarToWrite != null) storageIdentityGuard.writeSidecar(FileManager.getStorageIdentityFile(), sidecarToWrite);
        try (Connection validation = connections.openExistingReadOnly(databaseBase)) {
            if (storageIdentityGuard.inspect(validation, FileManager.getStorageIdentityFile()).status()
                    != StorageIdentityGuard.IdentityStatus.MATCH) {
                throw new StorageIdentityGuard.StorageIdentityException("Storage identity could not be confirmed after migration");
            }
        }
    }

    private void createAndValidateIdentitySafetyBackup(Connection connection) throws Exception {
        Path directory = FileManager.getRecoveryDirectory().resolve("identity-migration");
        recoveryFileOps.createDirectories(directory);
        Path target = directory.resolve("creditmanager_pre_identity_" + System.currentTimeMillis() + '-' + UUID.randomUUID() + ".zip");
        try (Statement statement = connection.createStatement()) {
            statement.execute("BACKUP TO '" + escapeSqlLiteral(target.toAbsolutePath().normalize().toString()) + "'");
        }
        BackupValidation validation = validateBackup(target);
        if (!validation.valid() || validation.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("Storage identity safety backup validation failed");
        }
    }

    private boolean isMissingDatabase(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 90146 || "90013".equals(sqlException.getSQLState()))) return true;
            String message = safe(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("existing database file is missing") || message.contains("database not found") && message.contains("ifexists")) return true;
        }
        return false;
    }

    private long archiveIdentityForExplicitFreshBootstrap() throws IOException {
        Path sidecar = FileManager.getStorageIdentityFile();
        if (!Files.isRegularFile(sidecar)) return 1L;
        long generation = 1L;
        try {
            generation = Math.addExact(storageIdentityGuard.readSidecar(sidecar).map(StorageIdentityGuard.StorageIdentity::generation).orElse(0L), 1L);
        } catch (RuntimeException ignored) {
        }
        Path archive = FileManager.getRecoveryDirectory().resolve("storage-identities")
                .resolve("storage_identity_before_fresh_" + System.currentTimeMillis() + '-' + UUID.randomUUID() + ".json");
        recoveryFileOps.moveWithoutReplacing(sidecar, archive);
        return Math.max(1L, generation);
    }

    private boolean isPhysicalRecoveryState() {
        return availability == DatabaseAvailability.PHYSICALLY_CORRUPT || availability == DatabaseAvailability.NEEDS_USER_RECOVERY;
    }

    private boolean isPhysicalH2Corruption(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && sqlException.getErrorCode() == 90030) return true;
            String type = current.getClass().getName().toLowerCase(Locale.ROOT);
            String message = safe(current.getMessage()).toLowerCase(Locale.ROOT);
            if (type.contains("mvstoreexception") || message.contains("file corrupt") || message.contains("datei fehlerhaft")
                    || message.contains("double mark") || message.contains("possible solution: use the recovery tool")
                    || message.contains("recovery werkzeug")) return true;
        }
        return false;
    }

    private void handlePhysicalCorruption(Throwable error, Path requestedPath) {
        healthy = false;
        writeLocked = true;
        availability = DatabaseAvailability.PHYSICALLY_CORRUPT;
        DataHealth.reportRecoveryRequired("Datenbank ist physisch beschädigt. Die aktive Datei wird nicht erneut geöffnet.");
        Path quarantined = quarantineActiveDatabase("PHYSICAL_H2_CORRUPTION", error);
        if ((quarantined != null || !Files.exists(FileManager.getDatabaseStorageFile())) && restoreLatestValidBackupInternal()) {
            initialized = false;
            initializedAt = null;
            healthy = true;
            writeLocked = false;
            availability = DatabaseAvailability.RESTORED_FROM_BACKUP;
            initialize();
            if (healthy) availability = DatabaseAvailability.RESTORED_FROM_BACKUP;
            return;
        }
        availability = DatabaseAvailability.NEEDS_USER_RECOVERY;
        initialized = true;
        initializedAt = requestedPath;
        DataHealth.reportRecoveryRequired("DB physisch beschädigt, keine valide Sicherung gefunden. Die quarantänisierte Datei wurde nicht gelöscht.");
    }

    public synchronized boolean createEmptyDatabaseAfterPhysicalRecovery() {
        if (availability != DatabaseAvailability.NEEDS_USER_RECOVERY || !FileManager.databaseAccessAllowed()
                || Files.exists(FileManager.getDatabaseStorageFile())) return false;
        explicitFreshBootstrap = true;
        try {
            initialized = false;
            initializedAt = null;
            healthy = true;
            writeLocked = false;
            availability = DatabaseAvailability.UNKNOWN;
            initialize();
            return isHealthy();
        } finally {
            explicitFreshBootstrap = false;
        }
    }

    public synchronized boolean isHealthy() { return healthy && !writeLocked; }
    public synchronized boolean isWriteLocked() { return writeLocked; }
    synchronized void updateBackupProtection(BackupCheckpointService.ProtectionState state) {
        backupProtectionState = state == null ? BackupCheckpointService.ProtectionState.DEGRADED : state;
        if (!healthy || writeLocked) return;
        if (backupProtectionState == BackupCheckpointService.ProtectionState.CRITICAL) {
            availability = DatabaseAvailability.BACKUP_PROTECTION_CRITICAL;
            DataHealth.clearReason(BACKUP_PROTECTION_DEGRADED_REASON);
            DataHealth.reportWarning(BACKUP_PROTECTION_CRITICAL_REASON,
                    "Deine letzten Änderungen wurden gespeichert. Neue Änderungen sind vorübergehend gesperrt, weil aktuell kein ausreichend aktuelles Sicherheitsbackup erstellt werden kann.");
        } else if (backupProtectionState == BackupCheckpointService.ProtectionState.HEALTHY) {
            if (availability == DatabaseAvailability.BACKUP_PROTECTION_DEGRADED
                    || availability == DatabaseAvailability.BACKUP_PROTECTION_CRITICAL) availability = DatabaseAvailability.HEALTHY;
            DataHealth.clearReason(BACKUP_PROTECTION_DEGRADED_REASON);
            DataHealth.clearReason(BACKUP_PROTECTION_CRITICAL_REASON);
        } else if (availability == DatabaseAvailability.HEALTHY
                || availability == DatabaseAvailability.RESTORED_FROM_BACKUP
                || availability == DatabaseAvailability.BACKUP_PROTECTION_CRITICAL) {
            availability = DatabaseAvailability.BACKUP_PROTECTION_DEGRADED;
            DataHealth.clearReason(BACKUP_PROTECTION_CRITICAL_REASON);
            DataHealth.reportWarning(BACKUP_PROTECTION_DEGRADED_REASON,
                    "Änderungen sind gespeichert. Der Backup-Schutz ist vorübergehend degradiert; automatische Wiederholungen laufen.");
        }
    }

    synchronized void updateBackupProtection(boolean degraded) {
        updateBackupProtection(degraded ? BackupCheckpointService.ProtectionState.DEGRADED
                : BackupCheckpointService.ProtectionState.HEALTHY);
    }
    public synchronized boolean isSafeForWrites() {
        if (!isHealthy() || !backupProtectionState.writesAllowed()) return false;
        try (Connection connection = connection()) {
            return !hasOpenHealthErrors(connection);
        } catch (SQLException exception) {
            if (isPhysicalH2Corruption(exception)) handlePhysicalCorruption(exception, FileManager.getDatabaseFile().toAbsolutePath());
            healthy = false;
            writeLocked = true;
            if (!isPhysicalRecoveryState()) availability = DatabaseAvailability.WRITE_LOCKED;
            return false;
        }
    }

    public synchronized boolean recheckAndRepair() {
        if (availability == DatabaseAvailability.SECONDARY_INSTANCE) {
            if (!FileManager.retryStorageLease()) return false;
            initialized = false;
            initializedAt = null;
            availability = DatabaseAvailability.UNKNOWN;
        }
        if (isPhysicalRecoveryState()) return restoreLatestValidBackup();
        initialized = false;
        initializedAt = null;
        availability = DatabaseAvailability.UNKNOWN;
        initialize();
        if (!isHealthy()) return false;
        return runHealthCheck().stream().noneMatch(record -> "ERROR".equals(record.severity()) && "OPEN".equals(record.status()));
    }
    public synchronized long revision() {
        initialize();
        if (isPhysicalRecoveryState()) throw new IllegalStateException("Database revision is unavailable during physical recovery");
        try (Connection connection = connection()) {
            String revision = metadata(connection, "data_revision");
            return revision == null ? 0L : Long.parseLong(revision);
        } catch (Exception exception) { throw new IllegalStateException("Database revision could not be read", exception); }
    }

    public synchronized boolean hasDomainData() {
        initialize();
        if (isPhysicalRecoveryState()) throw new IllegalStateException("Domain-data state is unknown during physical recovery");
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM credits) + (SELECT COUNT(*) FROM payments) + (SELECT COUNT(*) FROM credit_events) + (SELECT COUNT(*) FROM paylogs)")) {
            result.next();
            return result.getLong(1) > 0;
        } catch (SQLException exception) { throw new IllegalStateException("Domain-data state could not be read", exception); }
    }

    public synchronized boolean hasCompletedJsonMigration() {
        initialize();
        if (isPhysicalRecoveryState()) throw new IllegalStateException("Migration state is unknown during physical recovery");
        try (Connection connection = connection()) { return "COMPLETED".equals(metadata(connection, "json_migration_status")); }
        catch (SQLException exception) { throw new IllegalStateException("JSON migration state could not be read", exception); }
    }

    public synchronized boolean hasCompletedAutomaticJsonMigration() {
        initialize();
        if (isPhysicalRecoveryState()) throw new IllegalStateException("Migration state is unknown during physical recovery");
        try (Connection connection = connection()) { return "3".equals(metadata(connection, "json_auto_migration_version")); }
        catch (SQLException exception) { throw new IllegalStateException("Automatic migration state could not be read", exception); }
    }

    public synchronized boolean createBackup() {
        return createBackupArtifact(BackupArtifactType.RESTORABLE_HEALTHY).successful();
    }

    public synchronized ManualBackupResult createHealthyBackupNow() {
        BackupCreation result = createBackupArtifact(BackupArtifactType.RESTORABLE_HEALTHY);
        String message = !result.successful() ? "Gesundes Backup konnte nicht erstellt oder validiert werden."
                : result.mirrorSuccess() ? "Gesundes Backup wurde lokal und im unabhängigen Mirror gesichert."
                : "Gesundes Backup wurde lokal gesichert; der unabhängige Mirror ist derzeit nicht aktuell.";
        return new ManualBackupResult(result.successful(), result.mirrorSuccess(), result.revision(),
                result.localArtifact(), result.mirrorArtifact(), message);
    }

    synchronized BackupCheckpointService.CheckpointResult createBackupCheckpoint() {
        BackupCreation result = createBackupArtifact(BackupArtifactType.RESTORABLE_HEALTHY);
        return BackupCheckpointService.CheckpointResult.of(result.successful(), result.mirrorSuccess(), mirrorService().enabled(),
                result.revision(), result.createdAt());
    }

    public synchronized boolean createRecoverySnapshot() {
        return createBackupArtifact(BackupArtifactType.RECOVERY_SNAPSHOT).successful();
    }

    private BackupCreation createBackupArtifact(BackupArtifactType artifactType) {
        initialize();
        if (isPhysicalRecoveryState() || !FileManager.databaseAccessAllowed()) return BackupCreation.failed();
        Path source = FileManager.getDatabaseStorageFile();
        if (!Files.exists(source)) return BackupCreation.failed();
        long createdAt = System.currentTimeMillis();
        String prefix = artifactType == BackupArtifactType.RECOVERY_SNAPSHOT
                ? "creditmanager_recovery_snapshot_" : "creditmanager_backup_";
        Path target = FileManager.getBackupDirectory().resolve(prefix + createdAt + '-' + UUID.randomUUID() + ".zip");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            recoveryFileOps.createDirectories(target.getParent());
            statement.execute("BACKUP TO '" + escapeSqlLiteral(target.toAbsolutePath().normalize().toString()) + "'");
            BackupValidation validation = artifactType == BackupArtifactType.RECOVERY_SNAPSHOT
                    ? validateRecoverySnapshot(target) : validateBackup(target);
            if (!validation.valid() || validation.schemaVersion() != SCHEMA_VERSION) {
                recoveryFileOps.deleteIfExists(target);
                return BackupCreation.failed();
            }
            String activeStorageUuid = metadataDao.read(connection, "storage_uuid");
            boolean healthyArtifact = artifactType == BackupArtifactType.RESTORABLE_HEALTHY && !hasOpenHealthErrors(connection)
                    && activeStorageUuid != null && activeStorageUuid.equals(validation.storageUuid());
            long backupRevision = validation.revision();
            BackupManifestEntry localEntry = appendBackupManifest(new BackupManifestEntry(target.getFileName().toString(), createdAt, SCHEMA_VERSION,
                    backupRevision, validation.creditCount(), validation.paymentCount(), validation.paylogCount(), validation.eventCount(),
                    healthyArtifact, artifactType == BackupArtifactType.RECOVERY_SNAPSHOT ? "h2-recovery-snapshot" : "h2-backup",
                    null, 0L, 3, artifactType, healthyArtifact));
            if (artifactType == BackupArtifactType.RECOVERY_SNAPSHOT) {
                return new BackupCreation(true, backupRevision, createdAt, target, false, null);
            }
            if (!healthyArtifact) return BackupCreation.failed();
            BackupMirrorService.MirrorBackupEntry mirrorEntry = new BackupMirrorService.MirrorBackupEntry(
                    localEntry.fileName(), localEntry.createdAt(), localEntry.revision(), localEntry.schemaVersion(),
                    validation.storageUuid(), validation.generation(), localEntry.sha256(), localEntry.size(),
                    localEntry.creditCount(), localEntry.paymentCount(), localEntry.paylogCount(), localEntry.eventCount(),
                    localEntry.artifactType(), localEntry.healthy());
            BackupMirrorService.MirrorWriteResult mirror = mirrorService().mirror(target, mirrorEntry);
            if (!mirror.success()) CreditManagerClient.LOGGER.warn("CreditManager backup mirror update failed: {}", mirror.message());
            return new BackupCreation(true, backupRevision, createdAt, target, mirror.success(), mirror.artifact());
        } catch (Exception exception) {
            if (isPhysicalH2Corruption(exception)) {
                handlePhysicalCorruption(exception, FileManager.getDatabaseFile().toAbsolutePath());
            }
            CreditManagerClient.LOGGER.warn("Could not create CreditManager database {}", artifactType.name().toLowerCase(Locale.ROOT), exception);
            return BackupCreation.failed();
        }
    }

    public synchronized DatabaseState loadCreditState() {
        return loadCreditState(true);
    }

    public synchronized DatabaseState loadRuntimeCreditState() {
        return loadCreditState(false);
    }

    private DatabaseState loadCreditState(boolean includeEvents) {
        initialize();
        if (isPhysicalRecoveryState()) throw new IllegalStateException("CreditManager-Datenbank ist physisch beschädigt und wurde für die Wiederherstellung gesperrt.");
        try (Connection connection = connection()) {
            List<CreditEntry> credits = readCredits(connection, "SELECT * FROM credits", List.of());
            List<Payment> payments = loadPayments(connection);
            Map<UUID, List<Payment>> paymentsByCredit = new LinkedHashMap<>();
            for (Payment payment : payments) {
                paymentsByCredit.computeIfAbsent(payment.getCreditId(), ignored -> new ArrayList<>()).add(payment);
            }
            for (List<Payment> creditPayments : paymentsByCredit.values()) {
                creditPayments.sort(Comparator.comparingLong(Payment::getTimestamp));
            }
            for (CreditEntry credit : credits) {
                List<Payment> creditPayments = paymentsByCredit.getOrDefault(credit.getId(), List.of());
                java.math.BigInteger total = MoneyAggregate.sum(creditPayments, Payment::getAmountMinor);
                boolean valid = creditPayments.stream().allMatch(payment -> MoneyRules.isPositive(payment.getAmountMinor()))
                        && total.signum() >= 0 && total.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) <= 0
                        && total.compareTo(java.math.BigInteger.valueOf(credit.getAmountMinor())) <= 0
                        && total.equals(java.math.BigInteger.valueOf(credit.getPaidAmountMinor()));
                if (valid) {
                    credit.replacePayments(creditPayments);
                } else {
                    credit.setPayments(creditPayments);
                    healthInspectionService.storeHealth(connection, "CREDIT_PAYMENT_TOTAL", "ERROR", "credits",
                            credit.getId().toString(), "Zahlungssumme außerhalb des sicheren Bereichs",
                            "Die persistierte Zahlungssumme ist übergelaufen oder passt nicht zum Deal. Die Rohdaten bleiben sichtbar und unverändert.",
                            null, null);
                    writeLocked = true;
                    availability = DatabaseAvailability.WRITE_LOCKED;
                    DataHealth.reportRecoveryRequired("Persistierte Zahlungssumme ist inkonsistent; Rohdaten wurden ohne Normalisierung geladen.");
                }
            }
            return new DatabaseState(credits, payments, includeEvents ? loadEvents(connection) : List.of());
        } catch (SQLException exception) {
            if (isPhysicalH2Corruption(exception)) handlePhysicalCorruption(exception, FileManager.getDatabaseFile().toAbsolutePath());
            healthy = false;
            throw new IllegalStateException("CreditManager-Datenbank konnte nicht gelesen werden.", exception);
        }
    }

    public synchronized boolean replaceCreditState(Collection<CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) {
        return creditStateReplacementService.replace(credits, payments, events);
    }

    public synchronized boolean replaceCreditDataPreservingEvents(Collection<CreditEntry> credits, Collection<Payment> payments) {
        return creditStateReplacementService.replacePreservingEvents(credits, payments);
    }

    public synchronized boolean hasPendingAutomaticJsonMigration() {
        initialize();
        if (isPhysicalRecoveryState()) throw new IllegalStateException("Migration state is unknown during physical recovery");
        try (Connection connection = connection()) {
            return "IMPORT_COMMITTED".equals(metadata(connection, "json_migration_status"));
        } catch (SQLException exception) {
            throw new IllegalStateException("JSON-Migrationsstatus konnte nicht gelesen werden.", exception);
        }
    }

    public synchronized boolean commitCreditMutation(CreditMutation mutation) {
        MutationCommitReceipt receipt = commitCreditMutationWithReceipt(mutation);
        if (receipt.committed() && !receipt.verified()) {
            markRuntimeStateDegraded("Ein bestätigter Datenbank-Commit konnte nicht vollständig zurückgelesen werden.");
        }
        return receipt.committed();
    }

    public synchronized MutationCommitReceipt commitCreditMutationWithReceipt(CreditMutation mutation) {
        if (!isValidMutation(mutation)) return MutationCommitReceipt.notCommitted();
        long[] committedRevision = {-1L};
        boolean committed = inTransaction(DatabaseWriteMode.NORMAL, connection -> {
            long rowRevision = nextRevision(connection);
            applyCreditMutation(connection, mutation, rowRevision);
            bumpRevision(connection);
            committedRevision[0] = rowRevision;
        });
        if (!committed) return MutationCommitReceipt.notCommitted();
        try (Connection verification = connection()) {
            if (revision(verification) < committedRevision[0] || !mutationPersisted(verification, mutation, committedRevision[0])) {
                CreditManagerClient.LOGGER.warn("Committed CreditManager mutation could not be confirmed by the verification read; caller must reload authoritative state");
                return MutationCommitReceipt.committedUnverified(committedRevision[0]);
            }
            return MutationCommitReceipt.committed(committedRevision[0]);
        } catch (SQLException exception) {
            CreditManagerClient.LOGGER.error("Committed CreditManager mutation could not be verified; caller must reload authoritative state", exception);
            return MutationCommitReceipt.committedUnverified(committedRevision[0]);
        }
    }

    private boolean mutationPersisted(Connection connection, CreditMutation mutation, long rowRevision) throws SQLException {
        if (!rowHasRevision(connection, "credits", mutation.credit().getId(), rowRevision)) return false;
        for (Payment payment : mutation.paymentUpserts() == null ? List.<Payment>of() : mutation.paymentUpserts()) {
            if (!rowHasRevision(connection, "payments", payment.getId(), rowRevision)) return false;
        }
        for (UUID deletion : mutation.paymentDeletions() == null ? List.<UUID>of() : mutation.paymentDeletions()) {
            if (rowExists(connection, "payments", deletion.toString())) return false;
        }
        for (CreditEventEntry event : mutation.events() == null ? List.<CreditEventEntry>of() : mutation.events()) {
            if (!rowHasRevision(connection, "credit_events", event.getId(), rowRevision)) return false;
        }
        return true;
    }

    private boolean rowHasRevision(Connection connection, String table, UUID id, long expectedRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT revision FROM " + table + " WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getLong(1) == expectedRevision;
            }
        }
    }

    public synchronized void markRuntimeStateDegraded(String reason) {
        writeLocked = true;
        availability = DatabaseAvailability.WRITE_LOCKED;
        DataHealth.reportRecoveryRequired(reason == null || reason.isBlank()
                ? "Persistierte Daten konnten nicht mit dem Laufzeitstatus synchronisiert werden."
                : reason);
    }

    public synchronized boolean commitCreditMutationsBatch(Collection<CreditMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) return false;
        List<CreditMutation> values = List.copyOf(mutations);
        if (values.stream().anyMatch(mutation -> !isValidMutation(mutation))) return false;
        return inTransaction(connection -> {
            long rowRevision = nextRevision(connection);
            for (CreditMutation mutation : values) applyCreditMutation(connection, mutation, rowRevision);
            bumpRevision(connection);
        });
    }

    private boolean isValidMutation(CreditMutation mutation) {
        return mutation != null && mutation.credit() != null && mutation.credit().getId() != null;
    }

    private void applyCreditMutation(Connection connection, CreditMutation mutation, long rowRevision) throws SQLException {
        List<Payment> upserts = mutation.paymentUpserts() == null ? List.of() : List.copyOf(mutation.paymentUpserts());
        List<UUID> deletions = mutation.paymentDeletions() == null ? List.of() : List.copyOf(mutation.paymentDeletions());
        List<CreditEventEntry> events = mutation.events() == null ? List.of() : List.copyOf(mutation.events());
        Set<UUID> affectedPaylogs = new HashSet<>();
        for (Payment payment : upserts) {
            if (!isValidPayment(payment) || !mutation.credit().getId().equals(payment.getCreditId())) throw new SQLException("Invalid payment mutation");
            validatePaylogPaymentLink(connection, payment);
        }
        for (CreditEventEntry event : events) {
            if (!isValidEvent(event) || !mutation.credit().getId().equals(event.getCreditId())) throw new SQLException("Invalid credit event mutation");
        }

        upsertCredit(connection, mutation.credit(), rowRevision);
        try {
            faultInjector.hit(DatabaseFaultInjector.FailurePoint.AFTER_CREDIT_UPSERT);
        } catch (Exception exception) {
            throw new SQLException("Injected failure after credit upsert", exception);
        }
        for (UUID paymentId : deletions) {
            if (paymentId == null) throw new SQLException("Invalid payment deletion");
            UUID paylogId = paymentPaylogId(connection, paymentId);
            if (paylogId != null) affectedPaylogs.add(paylogId);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM payments WHERE id=? AND credit_id=?")) {
                statement.setString(1, paymentId.toString());
                statement.setString(2, mutation.credit().getId().toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Payment no longer exists");
            }
        }
        for (Payment payment : upserts) {
            UUID previousPaylogId = paymentPaylogId(connection, payment.getId());
            if (previousPaylogId != null) affectedPaylogs.add(previousPaylogId);
            if (payment.getPaylogId() != null) affectedPaylogs.add(payment.getPaylogId());
            upsertPayment(connection, payment, rowRevision);
        }
        try {
            faultInjector.hit(DatabaseFaultInjector.FailurePoint.AFTER_PAYMENT_UPSERT_BEFORE_EVENT);
            faultInjector.hit(DatabaseFaultInjector.FailurePoint.BEFORE_PAYLOG_AGGREGATE_REFRESH);
        } catch (Exception exception) {
            throw new SQLException("Injected failure before paylog aggregate refresh", exception);
        }
        refreshPaylogLinkAmounts(connection, affectedPaylogs);
        try {
            faultInjector.hit(DatabaseFaultInjector.FailurePoint.AFTER_PAYLOG_AGGREGATE_REFRESH_BEFORE_COMMIT);
        } catch (Exception exception) {
            throw new SQLException("Injected failure after paylog aggregate refresh", exception);
        }

        List<Payment> persistedPayments = loadPaymentsForCredit(connection, mutation.credit().getId());
        long persistedTotal = 0L;
        for (Payment payment : persistedPayments) persistedTotal = Math.addExact(persistedTotal, payment.getAmountMinor());
        validateDerivedCreditState(mutation.credit(), persistedTotal);
        validatePersistedPaylogLinks(connection, persistedPayments);
        for (CreditEventEntry event : events) upsertEvent(connection, event, rowRevision);
    }

    public synchronized boolean importLegacy(DatabaseState state, Collection<TransactionEntry> paylogs, String details) {
        if (state == null || paylogs == null) return false;
        return inTransaction(DatabaseWriteMode.MIGRATION, connection -> {
            if ("COMPLETED".equals(metadata(connection, "json_migration_status"))) return;
            if (hasDomainData(connection)) throw new SQLException("Existing CreditManager data must not be overwritten by JSON migration");
            validateState(connection, state.credits(), state.payments(), state.events());
            long rowRevision = nextRevision(connection);
            for (CreditEntry credit : state.credits()) upsertCredit(connection, credit, rowRevision);
            for (Payment payment : state.payments()) upsertPayment(connection, payment, rowRevision);
            for (CreditEventEntry event : state.events()) upsertEvent(connection, event, rowRevision);
            for (TransactionEntry entry : paylogs) insertPaylog(connection, entry, true, rowRevision);
            faultInjector.hit(DatabaseFaultInjector.FailurePoint.LEGACY_AFTER_DOMAIN_INSERT);
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO migration_log (id, migration_type, started_at, completed_at, details, status) VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, "JSON_V1");
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.setString(5, details == null ? "" : details);
                statement.setString(6, "COMPLETED");
                statement.executeUpdate();
            }
            putMetadata(connection, "json_migration_status", "COMPLETED");
            bumpRevision(connection);
        });
    }

    public synchronized AutomaticMigrationResult importLegacyAutomatically(DatabaseState state, Collection<TransactionEntry> paylogs,
                                                                              Collection<LegacyRecord> preserved, String details) {
        if (state == null || paylogs == null || preserved == null) return AutomaticMigrationResult.failed();
        int[] counts = new int[5];
        boolean committed = inTransaction(DatabaseWriteMode.MIGRATION, connection -> {
            String migrationId = UUID.randomUUID().toString();
            long rowRevision = nextRevision(connection);
            Map<UUID, UUID> canonicalPaylogIds = canonicalizeLegacyPaylogs(connection, paylogs, rowRevision, counts);
            Set<UUID> affectedPaylogs = new HashSet<>();
            for (Payment payment : state.payments()) {
                if (payment.getPaylogId() == null) continue;
                UUID canonical = canonicalPaylogIds.get(payment.getPaylogId());
                if (canonical == null) throw new SQLException("Legacy payment references an unknown paylog " + payment.getPaylogId());
                payment.setPaylogId(canonical);
                affectedPaylogs.add(canonical);
            }
            for (CreditEntry credit : state.credits()) {
                if (!rowExists(connection, "credits", credit.getId().toString())) {
                    upsertCredit(connection, credit, rowRevision);
                    counts[0]++;
                } else if (!sameCredit(connection, credit)) {
                    preserveLegacyRecord(connection, new LegacyRecord("CREDIT", credit.getId().toString(), GSON.toJson(credit),
                            "Abweichender JSON-Deal mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                }
            }
            for (Payment payment : state.payments()) {
                validatePaylogPaymentLink(connection, payment);
                if (!rowExists(connection, "payments", payment.getId().toString())) {
                    upsertPayment(connection, payment, rowRevision);
                    counts[1]++;
                } else if (!samePayment(connection, payment)) {
                    preserveLegacyRecord(connection, new LegacyRecord("PAYMENT", payment.getId().toString(), GSON.toJson(payment),
                            "Abweichende JSON-Zahlung mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                }
            }
            for (CreditEventEntry event : state.events()) {
                if (!rowExists(connection, "credit_events", event.getId().toString())) {
                    upsertEvent(connection, event, rowRevision);
                    counts[2]++;
                } else if (!sameEvent(connection, event)) {
                    preserveLegacyRecord(connection, new LegacyRecord("EVENT", event.getId().toString(), GSON.toJson(event),
                            "Abweichendes JSON-Event mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                }
            }
            for (TransactionEntry paylog : paylogs) {
                if (paylog.getId() != null && rowExists(connection, "paylogs", paylog.getId().toString())) {
                    if (!samePaylog(connection, paylog)) {
                        preserveLegacyRecord(connection, new LegacyRecord("PAYLOG", paylog.getId().toString(), GSON.toJson(paylog),
                                "Abweichender JSON-Paylog mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                    }
                } else if (insertPaylog(connection, paylog, true, rowRevision)) counts[3]++;
            }
            faultInjector.hit(DatabaseFaultInjector.FailurePoint.LEGACY_AFTER_DOMAIN_INSERT);
            refreshPaylogLinkAmounts(connection, affectedPaylogs);
            for (CreditEntry credit : state.credits()) {
                List<Payment> persisted = loadPaymentsForCredit(connection, credit.getId());
                long total = 0L;
                for (Payment payment : persisted) total = Math.addExact(total, payment.getAmountMinor());
                validateDerivedCreditState(credit, total);
                validatePersistedPaylogLinks(connection, persisted);
            }
            for (LegacyRecord record : preserved) {
                preserveLegacyRecord(connection, record, migrationId, counts);
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO migration_log (id, migration_type, started_at, completed_at, details, status) VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, migrationId);
                statement.setString(2, "JSON_AUTO_V3");
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.setString(5, details == null ? "" : details);
                statement.setString(6, "IMPORT_COMMITTED");
                statement.executeUpdate();
            }
            putMetadata(connection, "json_migration_status", "IMPORT_COMMITTED");
            bumpRevision(connection);
        });
        return committed ? new AutomaticMigrationResult(true, counts[0], counts[1], counts[2], counts[3], counts[4]) : AutomaticMigrationResult.failed();
    }

    public synchronized boolean completeAutomaticJsonMigration(String details) {
        return inTransaction(DatabaseWriteMode.MIGRATION, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE migration_log SET status='COMPLETED',completed_at=?,details=? WHERE migration_type='JSON_AUTO_V3' AND status='IMPORT_COMMITTED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, details == null ? "" : details);
                statement.executeUpdate();
            }
            putMetadata(connection, "json_migration_status", "COMPLETED");
            putMetadata(connection, "json_auto_migration_version", "3");
            bumpRevision(connection);
        });
    }

    private Map<UUID, UUID> canonicalizeLegacyPaylogs(Connection connection, Collection<TransactionEntry> paylogs,
                                                       long rowRevision, int[] counts) throws SQLException {
        List<TransactionEntry> existing = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM paylogs"); ResultSet result = statement.executeQuery()) {
            while (result.next()) existing.add(readPaylog(result));
        }
        Map<UUID, UUID> mapping = new LinkedHashMap<>();
        for (TransactionEntry value : existing) mapping.put(value.getId(), value.getId());
        for (TransactionEntry legacy : paylogs) {
            if (!isValidPaylog(legacy) || legacy.getId() == null) throw new SQLException("Invalid legacy paylog");
            UUID legacyId = legacy.getId();
            List<TransactionEntry> semanticMatches = existing.stream().filter(value -> samePaylogSemantic(value, legacy)).toList();
            if (semanticMatches.size() > 1) throw new SQLException("Ambiguous canonical paylog identity for " + legacyId);
            if (semanticMatches.size() == 1) {
                UUID canonicalId = semanticMatches.getFirst().getId();
                mapping.put(legacyId, canonicalId);
                legacy.setId(canonicalId);
                continue;
            }
            if (rowExists(connection, "paylogs", legacyId.toString())) throw new SQLException("Conflicting legacy paylog UUID " + legacyId);
            String normalized = blank(legacy.getNormalizedText()) ? PaylogSearchText.build(legacy) : legacy.getNormalizedText();
            legacy.setNormalizedText(normalized);
            legacy.setHash(paylogHash(legacy, normalized));
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM paylogs WHERE entry_hash=?")) {
                statement.setString(1, legacy.getHash());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        TransactionEntry collision = readPaylog(result);
                        if (!samePaylogSemantic(collision, legacy)) throw new SQLException("Canonical paylog hash collision");
                        mapping.put(legacyId, collision.getId());
                        legacy.setId(collision.getId());
                        continue;
                    }
                }
            }
            if (!insertPaylog(connection, legacy, false, rowRevision)) throw new SQLException("Legacy paylog insert failed");
            existing.add(legacy);
            mapping.put(legacyId, legacy.getId());
            counts[3]++;
        }
        return Map.copyOf(mapping);
    }

    private boolean samePaylogSemantic(TransactionEntry left, TransactionEntry right) {
        return safe(left.getFromPlayer()).equalsIgnoreCase(safe(right.getFromPlayer()))
                && safe(left.getToPlayer()).equalsIgnoreCase(safe(right.getToPlayer()))
                && left.getAmountMinor() == right.getAmountMinor()
                && Objects.equals(left.getRawText(), right.getRawText())
                && left.getTimestamp() == right.getTimestamp()
                && Objects.equals(left.getSource(), right.getSource())
                && Objects.equals(left.getMetadata(), right.getMetadata());
    }

    public synchronized int legacyRecordCount() {
        initialize();
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM legacy_records")) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) { throw new IllegalStateException("Legacy-record count could not be read", exception); }
    }

    public synchronized boolean addPaylog(TransactionEntry entry) {
        if (!isValidPaylog(entry)) return false;
        return inTransaction(connection -> {
            if (!insertPaylog(connection, entry, true, nextRevision(connection))) throw new DuplicatePaylogException();
            bumpRevision(connection);
        });
    }

    public synchronized boolean resetCorruptDatabaseWithBackup() {
        initialize();
        if (!FileManager.databaseAccessAllowed()) return false;
        boolean restored = restoreLatestValidBackupInternal();
        if (!restored) {
            healthy = false;
            writeLocked = true;
            DataHealth.reportRecoveryRequired("Keine valide Datenbank-Sicherung zur Wiederherstellung gefunden. Die aktive Datenbank wurde nicht gelöscht.");
            return false;
        }
        initialized = false;
        initializedAt = null;
        healthy = true;
        writeLocked = false;
        try {
            initialize();
            return isHealthy();
        } catch (RuntimeException exception) {
            healthy = false;
            writeLocked = true;
            return false;
        }
    }

    public synchronized boolean restoreLatestValidBackup() {
        FileManager.initialize();
        if (!FileManager.databaseAccessAllowed()) return false;
        if (availability == DatabaseAvailability.STORAGE_CONFLICT) return false;
        try {
            connections.loadDriver();
        } catch (ClassNotFoundException exception) {
            healthy = false;
            writeLocked = true;
            availability = DatabaseAvailability.WRITE_LOCKED;
            CreditManagerClient.LOGGER.error("H2 driver is unavailable for CreditManager restore", exception);
            return false;
        }
        boolean restored = restoreLatestValidBackupInternal();
        if (!restored) return false;
        initialized = false;
        initializedAt = null;
        healthy = true;
        writeLocked = false;
        try {
            initialize();
            return isHealthy();
        } catch (RuntimeException exception) {
            healthy = false;
            writeLocked = true;
            return false;
        }
    }

    public synchronized List<BackupManifestEntry> listBackups() {
        if (!FileManager.databaseAccessAllowed()) return List.of();
        return listAvailableBackups().stream().map(AvailableBackup::entry).toList();
    }

    public synchronized List<AvailableBackup> listAvailableBackups() {
        if (!FileManager.databaseAccessAllowed()) return List.of();
        Map<String, AvailableBackup> available = new LinkedHashMap<>();
        for (BackupCandidate candidate : discoverBackupCandidates()) {
            BackupManifestEntry entry = candidate.entry();
            String key = safe(entry.sha256()) + ':' + entry.revision();
            AvailableBackup prior = available.get(key);
            BackupSource source = prior == null ? candidate.source()
                    : prior.source() == candidate.source() ? candidate.source() : BackupSource.LOCAL_AND_MIRROR;
            BackupManifestEntry selected = prior == null || entry.createdAt() > prior.entry().createdAt() ? entry : prior.entry();
            available.put(key, new AvailableBackup(selected, source));
        }
        return available.values().stream()
                .sorted(Comparator.comparingLong((AvailableBackup value) -> value.entry().revision()).reversed()
                        .thenComparing(Comparator.comparingLong((AvailableBackup value) -> value.entry().createdAt()).reversed()))
                .toList();
    }

    private boolean activeDatabaseIsUnexpectedlyEmpty() {
        try (Connection connection = connection()) {
            if (hasDomainData(connection)) return false;
            return discoverBackupCandidates().stream()
                    .anyMatch(candidate -> candidate.entry().automaticRestoreEligible() && candidate.entry().domainCount() > 0);
        } catch (Exception exception) {
            throw new IllegalStateException("Empty-database safety check could not be completed", exception);
        }
    }

    private boolean restoreLatestValidBackupInternal() {
        if (recovering) return false;
        recovering = true;
        lifecycleLock.writeLock().lock();
        try {
            List<BackupCandidate> candidates = discoverBackupCandidates().stream()
                    .filter(candidate -> candidate.entry().automaticRestoreEligible())
                    .sorted(Comparator.comparingLong((BackupCandidate candidate) -> candidate.entry().revision()).reversed()
                            .thenComparing(Comparator.comparingLong((BackupCandidate candidate) -> candidate.entry().createdAt()).reversed()))
                    .toList();
            BackupIdentitySelection identity = selectBackupIdentity(candidates);
            if (!identity.safe()) return false;
            for (BackupCandidate candidate : candidates) {
                BackupManifestEntry entry = candidate.entry();
                Path archive = candidate.path();
                boolean zip = archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
                BackupValidation validation = zip ? validateBackup(archive) : validateLegacyBackupCandidate(archive);
                if (!validation.valid() || validation.domainCount() < entry.domainCount()
                        || !identity.matches(validation.storageUuid(), validation.generation())) continue;
                if (!(zip ? restoreBackupArchive(archive) : restoreLegacyDatabaseFile(archive))) continue;
                DataHealth.reportRecoveryRequired("Valide Datenbanksicherung wurde wiederhergestellt; die vorherige Datenbank liegt in der Quarantäne.");
                return true;
            }
            return false;
        } finally {
            recovering = false;
            lifecycleLock.writeLock().unlock();
        }
    }

    private boolean restoreBackupArchive(Path archive) {
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("restore-" + UUID.randomUUID());
        try {
            Path extracted = extractBackup(archive, staging);
            Path databaseFile = findDatabaseFile(extracted);
            if (databaseFile == null || !prepareRestoreCandidate(databaseFile)) return false;
            return installValidatedDatabase(databaseFile, "BACKUP_RESTORE");
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not restore CreditManager backup {}", archive, exception);
            return false;
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private boolean restoreLegacyDatabaseFile(Path legacy) {
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("legacy-restore-" + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            Path copy = staging.resolve("creditmanager.mv.db");
            recoveryFileOps.copy(legacy, copy, StandardCopyOption.COPY_ATTRIBUTES);
            if (!prepareRestoreCandidate(copy)) return false;
            return installValidatedDatabase(copy, "LEGACY_BACKUP_RESTORE");
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not restore legacy CreditManager database backup {}", legacy, exception);
            return false;
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private boolean quarantineActiveDatabase(Path active) {
        return quarantineActiveDatabase("BACKUP_RESTORE", null) != null;
    }

    private Path quarantineActiveDatabase(String reason, Throwable error) {
        Path active = FileManager.getDatabaseStorageFile();
        if (!Files.exists(active)) return null;
        Path quarantine = FileManager.getQuarantineDirectory();
        Path target = quarantine.resolve("creditmanager_" + System.currentTimeMillis() + '_' + UUID.randomUUID() + ".mv.db");
        try {
            Files.createDirectories(quarantine);
            moveWithoutReplacing(active, target);
            quarantineSidecar("creditmanager.trace.db", quarantine);
            quarantineSidecar("creditmanager.lock.db", quarantine);
            writeQuarantineManifest(target, reason, error, "MOVED");
            return target;
        } catch (Exception exception) {
            try {
                Files.createDirectories(quarantine);
                recoveryFileOps.copy(active, target, StandardCopyOption.COPY_ATTRIBUTES);
                writeQuarantineManifest(target, reason, error, "COPIED_ACTIVE_FILE_REMAINS");
            } catch (Exception copyFailure) {
                CreditManagerClient.LOGGER.error("Could not quarantine active CreditManager database", copyFailure);
            }
            return null;
        }
    }

    private void quarantineSidecar(String name, Path quarantine) {
        Path source = FileManager.getDataDirectory().resolve(name);
        if (!Files.isRegularFile(source)) return;
        try { moveWithoutReplacing(source, quarantine.resolve(source.getFileName() + "." + UUID.randomUUID())); }
        catch (IOException ignored) { }
    }

    private void writeQuarantineManifest(Path quarantined, String reason, Throwable error, String outcome) {
        try {
            Map<String, String> manifest = new LinkedHashMap<>();
            manifest.put("reason", reason == null ? "UNKNOWN" : reason);
            manifest.put("outcome", outcome);
            manifest.put("created_at", String.valueOf(System.currentTimeMillis()));
            manifest.put("original", String.valueOf(FileManager.getDatabaseStorageFile()));
            manifest.put("quarantined", String.valueOf(quarantined));
            if (error != null) {
                manifest.put("error_type", error.getClass().getName());
                manifest.put("error_message", safe(error.getMessage()));
                StringWriter writer = new StringWriter();
                error.printStackTrace(new PrintWriter(writer));
                manifest.put("stacktrace", writer.toString());
            }
            Path manifestFile = quarantined.resolveSibling(quarantined.getFileName() + ".manifest.json");
            Files.writeString(manifestFile, GSON.toJson(manifest), StandardCharsets.UTF_8);
        } catch (IOException manifestFailure) {
            CreditManagerClient.LOGGER.warn("Could not write CreditManager quarantine manifest", manifestFailure);
        }
    }

    private BackupValidation validateBackup(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) return BackupValidation.invalid();
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("validate-" + UUID.randomUUID());
        try {
            Path extracted = extractBackup(archive, staging);
            Path databaseFile = findDatabaseFile(extracted);
            return databaseFile == null ? BackupValidation.invalid() : validateDatabaseFile(databaseFile, true, false);
        } catch (Exception exception) {
            return BackupValidation.invalid();
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private BackupIdentitySelection selectBackupIdentity(List<BackupCandidate> candidates) {
        try {
            Optional<StorageIdentityGuard.StorageIdentity> expected = storageIdentityGuard.readSidecar(FileManager.getStorageIdentityFile());
            if (expected.isPresent()) {
                StorageIdentityGuard.StorageIdentity identity = expected.get();
                return BackupIdentitySelection.safe(identity.storageUuid(), identity.generation());
            }
        } catch (IOException | RuntimeException exception) {
            markBackupIdentityConflict("Die Storage-Identität ist ungültig; Backups wurden nicht automatisch ausgewählt.");
            return BackupIdentitySelection.unsafe();
        }
        Set<BackupIdentity> identities = new HashSet<>();
        for (BackupCandidate candidate : candidates) {
            if (!blank(candidate.storageUuid()) && candidate.generation() > 0L) {
                identities.add(new BackupIdentity(candidate.storageUuid(), candidate.generation()));
            }
        }
        if (identities.size() != 1) {
            if (identities.size() > 1) {
                markBackupIdentityConflict("Mehrere unabhängige Storage-Generationen wurden in den Backups gefunden. Es wurde nichts automatisch wiederhergestellt.");
            }
            return BackupIdentitySelection.unsafe();
        }
        BackupIdentity identity = identities.iterator().next();
        return BackupIdentitySelection.safe(identity.storageUuid(), identity.generation());
    }

    private void markBackupIdentityConflict(String reason) {
        healthy = false;
        writeLocked = true;
        availability = DatabaseAvailability.STORAGE_CONFLICT;
        DataHealth.reportRecoveryRequired(reason);
    }

    private Path extractBackup(Path archive, Path staging) throws IOException {
        final int maximumEntries = 256;
        final long maximumEntryBytes = 1_073_741_824L;
        final long maximumTotalBytes = 2_147_483_648L;
        Files.createDirectories(staging);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > maximumEntries) throw new IOException("Backup contains too many entries");
            long totalBytes = 0L;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getSize() > maximumEntryBytes) throw new IOException("Backup entry is too large");
                if (entry.getSize() > 16_777_216L && entry.getCompressedSize() > 0L
                        && entry.getSize() / entry.getCompressedSize() > 200L) throw new IOException("Backup entry compression ratio is unsafe");
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging)) throw new IOException("Unsafe backup entry");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target)) {
                        byte[] buffer = new byte[16_384];
                        long entryBytes = 0L;
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            entryBytes += read;
                            totalBytes += read;
                            if (entryBytes > maximumEntryBytes || totalBytes > maximumTotalBytes) throw new IOException("Backup extraction limit exceeded");
                            output.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
        return staging;
    }

    private Path findDatabaseFile(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            List<Path> databases = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".mv.db"))
                    .toList();
            if (databases.size() != 1) throw new IOException("Backup must contain exactly one H2 database");
            return databases.getFirst();
        }
    }

    private BackupValidation validateLegacyBackupCandidate(Path databaseFile) {
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("legacy-validate-" + UUID.randomUUID());
        try {
            recoveryFileOps.createDirectories(staging);
            Path copy = staging.resolve("creditmanager.mv.db");
            recoveryFileOps.copy(databaseFile, copy, StandardCopyOption.COPY_ATTRIBUTES);
            return validateDatabaseFile(copy);
        } catch (Exception exception) {
            return BackupValidation.invalid();
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private BackupValidation validateRecoverySnapshot(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) return BackupValidation.invalid();
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("discard-validate-" + UUID.randomUUID());
        try {
            Path extracted = extractBackup(archive, staging);
            Path databaseFile = findDatabaseFile(extracted);
            return databaseFile == null ? BackupValidation.invalid() : validateDatabaseFile(databaseFile, false);
        } catch (Exception exception) {
            return BackupValidation.invalid();
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private boolean installValidatedDatabase(Path stagedDatabase, String reason) {
        Path active = FileManager.getDatabaseStorageFile();
        Path original = null;
        String originalChecksum = null;
        try {
            Files.createDirectories(active.getParent());
            if (Files.isRegularFile(active)) {
                originalChecksum = sha256File(active);
                original = FileManager.getQuarantineDirectory().resolve("creditmanager_" + System.currentTimeMillis() + '_' + UUID.randomUUID() + ".mv.db");
                Files.createDirectories(original.getParent());
                moveWithoutReplacing(active, original);
                if (!Files.isRegularFile(original)) throw new IOException("Quarantined original is missing");
                writeQuarantineManifest(original, reason, null, "MOVED_FOR_RESTORE");
            }
            moveWithoutReplacing(stagedDatabase, active);
            BackupValidation installed = validateDatabaseFile(active);
            if (!installed.valid() || installed.schemaVersion() != SCHEMA_VERSION) {
                throw new IOException("Installed backup failed post-install validation");
            }
            return true;
        } catch (Exception installFailure) {
            Path failedInstall = FileManager.getRecoveryDirectory().resolve("failed-install-" + System.currentTimeMillis() + '-' + UUID.randomUUID() + ".mv.db");
            try {
                if (Files.exists(active)) moveWithoutReplacing(active, failedInstall);
                if (original != null && Files.isRegularFile(original)) {
                    moveWithoutReplacing(original, active);
                    if (!Objects.equals(originalChecksum, sha256File(active))) throw new IOException("Restored original checksum mismatch");
                } else if (Files.isRegularFile(stagedDatabase)) {
                    recoveryFileOps.copy(stagedDatabase, failedInstall, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (Exception rollbackFailure) {
                installFailure.addSuppressed(rollbackFailure);
            }
            healthy = false;
            writeLocked = true;
            availability = DatabaseAvailability.NEEDS_USER_RECOVERY;
            DataHealth.reportRecoveryRequired("Datenbank-Wiederherstellung ist fehlgeschlagen. Original und Installationskandidat wurden im Recovery-Verzeichnis erhalten.");
            CreditManagerClient.LOGGER.error("CreditManager restore installation failed; recovery artifacts were retained", installFailure);
            return false;
        }
    }

    private boolean prepareRestoreCandidate(Path databaseFile) {
        BackupValidation initial = validateDatabaseFile(databaseFile);
        if (!initial.valid() || !DatabaseSchemaSupportPolicy.supports(initial.schemaVersion())) return false;
        try {
            if (DatabaseSchemaSupportPolicy.requiresMigration(initial.schemaVersion())) {
                migrationService.migrateRestoreCandidate(databaseFile, initial.schemaVersion());
            }
            BackupValidation current = validateDatabaseFile(databaseFile);
            return current.valid() && current.schemaVersion() == SCHEMA_VERSION
                    && current.creditCount() == initial.creditCount()
                    && current.paymentCount() == initial.paymentCount()
                    && current.paylogCount() == initial.paylogCount()
                    && current.eventCount() == initial.eventCount();
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not migrate and validate CreditManager restore candidate {}", databaseFile, exception);
            return false;
        }
    }

    private BackupValidation validateDatabaseFile(Path databaseFile) {
        return validateDatabaseFile(databaseFile, true);
    }

    private BackupValidation validateDatabaseFile(Path databaseFile, boolean requireLogicalHealth) {
        return validateDatabaseFile(databaseFile, requireLogicalHealth, true);
    }

    private BackupValidation validateDatabaseFile(Path databaseFile, boolean requireLogicalHealth, boolean repairDerivedState) {
        String name = databaseFile.getFileName().toString();
        if (!name.endsWith(".mv.db")) return BackupValidation.invalid();
        Path base = databaseFile.resolveSibling(name.substring(0, name.length() - ".mv.db".length()));
        try (Connection connection = openConnection(base); Statement statement = connection.createStatement()) {
            schemaManager.ensureMetadataTable(connection);
            int installed = schemaManager.installedSchemaVersion(connection);
            if (installed == SCHEMA_VERSION) {
                if (repairDerivedState) {
                    schemaManager.ensureAdditiveSchemaObjects(connection);
                    schemaManager.rebuildEventCounts(connection);
                }
                schemaManager.validateRequiredSchema(connection);
                if (requireLogicalHealth) validateBackupDomain(connection);
            } else if (DatabaseSchemaSupportPolicy.requiresMigration(installed)) {
                if (requireLogicalHealth) validateLegacyBackupDomain(connection);
            } else {
                return BackupValidation.invalid();
            }
            int credits = countRows(statement, "credits");
            int payments = countRows(statement, "payments");
            int paylogs = countRows(statement, "paylogs");
            int events = countRows(statement, "credit_events");
            if (!repairDerivedState && countEventSummary(statement) != events) return BackupValidation.invalid();
            String generation = metadataDao.read(connection, "storage_generation");
            long storageGeneration = generation == null ? 0L : Long.parseLong(generation);
            return new BackupValidation(true, credits, payments, paylogs, events, installed, revision(connection),
                    metadataDao.read(connection, "storage_uuid"), storageGeneration);
        } catch (Exception exception) {
            return BackupValidation.invalid();
        }
    }

    private long countEventSummary(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COALESCE(SUM(event_count),0) FROM credit_event_counts")) {
            result.next();
            return result.getLong(1);
        }
    }

    private boolean backupMatchesExpectedIdentity(BackupValidation validation) {
        try {
            Optional<StorageIdentityGuard.StorageIdentity> expected = storageIdentityGuard.readSidecar(FileManager.getStorageIdentityFile());
            return expected.isPresent() && expected.get().storageUuid().equals(validation.storageUuid());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void validateBackupDomain(Connection connection) throws SQLException {
        requireZero(connection, "SELECT COUNT(*) FROM credits c LEFT JOIN (SELECT credit_id,SUM(amount) total FROM payments GROUP BY credit_id) p ON p.credit_id=c.id WHERE c.amount<=0 OR c.paid_amount<0 OR c.paid_amount>c.amount OR c.paid_amount<>COALESCE(p.total,0) OR (c.status NOT IN ('CLOSED','CANCELLED') AND c.status<>CASE WHEN c.paid_amount=0 THEN 'OPEN' WHEN c.paid_amount=c.amount THEN 'PAID' ELSE 'PARTIAL' END) OR ((c.status IN ('PAID','CLOSED','CANCELLED'))<>(c.completed_at IS NOT NULL))", "invalid credit aggregate");
        requireZero(connection, "SELECT COUNT(*) FROM payments p LEFT JOIN credits c ON c.id=p.credit_id LEFT JOIN paylogs l ON l.id=p.paylog_id WHERE p.amount<=0 OR c.id IS NULL OR p.payment_kind NOT IN ('MONEY','ITEM') OR (p.paylog_id IS NOT NULL AND (l.id IS NULL OR LOWER(COALESCE(p.from_player,''))<>LOWER(COALESCE(l.payer,'')) OR LOWER(COALESCE(p.to_player,''))<>LOWER(COALESCE(l.receiver,'')) OR p.amount>l.amount OR UPPER(COALESCE(p.source,'')) NOT LIKE 'PAYLOG_%'))", "invalid payment graph");
        requireZero(connection, "SELECT COUNT(*) FROM credit_events e LEFT JOIN credits c ON c.id=e.credit_id WHERE c.id IS NULL OR e.amount<0 OR e.paid_after<0 OR e.remaining_after<0 OR e.amount_before<0 OR e.amount_after<0", "invalid event graph");
        requireZero(connection, "SELECT COUNT(*) FROM credit_event_counts c LEFT JOIN (SELECT credit_id,COUNT(*) event_count FROM credit_events GROUP BY credit_id) e ON e.credit_id=c.credit_id WHERE e.credit_id IS NULL OR e.event_count<>c.event_count", "invalid event count summary");
        requireZero(connection, "SELECT COUNT(*) FROM (SELECT credit_id,COUNT(*) event_count FROM credit_events GROUP BY credit_id) e LEFT JOIN credit_event_counts c ON c.credit_id=e.credit_id WHERE c.credit_id IS NULL OR c.event_count<>e.event_count", "missing event count summary");
        requireZero(connection, "SELECT COUNT(*) FROM paylogs l LEFT JOIN (SELECT paylog_id,SUM(amount) total,COUNT(*) count_value FROM payments WHERE paylog_id IS NOT NULL GROUP BY paylog_id) p ON p.paylog_id=l.id WHERE l.amount<=0 OR l.linked_amount<>COALESCE(p.total,0) OR l.link_count<>COALESCE(p.count_value,0) OR COALESCE(p.total,0)>l.amount", "invalid paylog aggregate");
        try (PreparedStatement statement = connection.prepareStatement("SELECT id,payment_kind,items_json,item_nbt_entries FROM payments"); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                List<String> items = strictStringList(result.getString("items_json"), "payments", result.getString("id"), "items_json");
                strictStringList(result.getString("item_nbt_entries"), "payments", result.getString("id"), "item_nbt_entries");
                if (PaymentKind.ITEM.name().equals(result.getString("payment_kind")) && items.isEmpty()) throw new SQLException("Item payment has no item payload");
            }
        }
        if (hasOpenHealthErrors(connection)) throw new SQLException("Backup contains unresolved logical errors");
    }

    private void validateLegacyBackupDomain(Connection connection) throws SQLException {
        for (String table : List.of("credits", "payments", "credit_events", "paylogs")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0").close();
            }
        }
        requireZero(connection, "SELECT COUNT(*) FROM credits WHERE amount<=0 OR paid_amount<0 OR paid_amount>amount", "invalid legacy credit amount");
        requireZero(connection, "SELECT COUNT(*) FROM payments WHERE amount<=0", "invalid legacy payment amount");
        requireZero(connection, "SELECT COUNT(*) FROM paylogs WHERE amount<=0", "invalid legacy paylog amount");
    }

    private void requireZero(Connection connection, String sql, String message) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            if (result.getLong(1) != 0L) throw new SQLException(message);
        }
    }

    private int countRows(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private List<BackupManifestEntry> readBackupManifest() {
        Path manifest = FileManager.getBackupManifestFile();
        if (!Files.isRegularFile(manifest)) return List.of();
        try {
            List<BackupManifestEntry> entries = GSON.fromJson(Files.readString(manifest), BACKUP_MANIFEST_LIST);
            return entries == null ? List.of() : entries.stream().filter(entry -> entry != null && safeBackupPath(entry.fileName()) != null).toList();
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not read CreditManager backup manifest", exception);
            return List.of();
        }
    }

    private BackupManifestEntry appendBackupManifest(BackupManifestEntry entry) throws IOException {
        List<BackupManifestEntry> entries = new ArrayList<>(readBackupManifest());
        entries.removeIf(value -> value.fileName().equals(entry.fileName()));
        Path archive = safeBackupPath(entry.fileName());
        BackupManifestEntry published = new BackupManifestEntry(entry.fileName(), entry.createdAt(), entry.schemaVersion(), entry.revision(),
                entry.creditCount(), entry.paymentCount(), entry.paylogCount(), entry.eventCount(), entry.healthy(), entry.format(),
                archive == null ? null : sha256File(archive), archive == null ? 0L : fileSize(archive), 3,
                entry.artifactType(), entry.automaticRestoreEligible());
        entries.add(published);
        entries.sort(Comparator.comparingLong(BackupManifestEntry::createdAt).reversed());
        List<BackupManifestEntry> retained = retainedBackups(entries);
        archiveExcessBackups(entries, retained);
        writeBackupManifest(retained);
        return published;
    }

    private void writeBackupManifest(List<BackupManifestEntry> entries) throws IOException {
        Path manifest = FileManager.getBackupManifestFile();
        recoveryFileOps.createDirectories(manifest.getParent());
        Path temporary = manifest.resolveSibling(manifest.getFileName() + ".tmp");
        recoveryFileOps.writeString(temporary, GSON.toJson(entries), StandardCharsets.UTF_8);
        recoveryFileOps.moveReplacing(temporary, manifest);
    }

    private void moveWithoutReplacing(Path source, Path target) throws IOException {
        recoveryFileOps.moveWithoutReplacing(source, target);
    }

    private void deleteTemporaryTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private long modifiedAt(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException exception) { throw new IllegalStateException("Backup timestamp could not be read: " + path, exception); }
    }

    public synchronized Optional<TransactionEntry> findPaylog(UUID id) {
        if (id == null) return Optional.empty();
        initialize();
        return executeQueryWithSchemaRetry("Paylog konnte nicht aus der Datenbank geladen werden.", () -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("SELECT p.id, p.payer, p.receiver, p.amount, p.raw_text, p.normalized_text, p.created_at, p.entry_hash, p.source, p.metadata, p.linked_amount, (SELECT COUNT(paylog_id) FROM payments WHERE 1=0) AS schema_guard FROM paylogs p WHERE p.id=?")) {
                statement.setString(1, id.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readPaylog(result)) : Optional.empty();
                }
            }
        });
    }

    public synchronized int addPaylogsBatch(Collection<TransactionEntry> entries) {
        return addPaylogsBatchDetailed(entries).inserted();
    }

    public synchronized BatchInsertResult addPaylogsBatchDetailed(Collection<TransactionEntry> entries) {
        if (entries == null || entries.isEmpty()) return new BatchInsertResult(0, 0, 0, 0, List.of());
        List<TransactionEntry> values = new ArrayList<>(entries);
        List<String> warnings = new ArrayList<>();
        List<TransactionEntry> validEntries = new ArrayList<>(values.size());
        for (TransactionEntry entry : values) {
            if (isValidPaylog(entry)) validEntries.add(entry);
            else warnings.add("Ungültiger Paylog wurde nicht gespeichert.");
        }
        final int[] inserted = {0};
        final int[] skipped = {values.size() - validEntries.size()};
        boolean committed = inTransaction(connection -> {
            long rowRevision = nextRevision(connection);
            for (TransactionEntry entry : validEntries) {
                if (insertPaylog(connection, entry, true, rowRevision)) inserted[0]++;
                else skipped[0]++;
            }
            if (inserted[0] > 0) bumpRevision(connection);
        });
        if (!committed) return new BatchInsertResult(values.size(), 0, 0, values.size(), List.of("Paylog-Batch wurde nicht gespeichert."));
        return new BatchInsertResult(values.size(), inserted[0], skipped[0], 0, List.copyOf(warnings));
    }

    public List<TransactionEntry> queryPaylogs(String player, int direction, String query, int limit, int offset) {
        initialize();
        return readQuery(() -> queryService.queryPaylogs(player, direction, query, limit, offset));
    }

    public QueryPage<TransactionEntry> queryPaylogPage(String player, int direction, String query, int limit, int offset) {
        initialize();
        return readQuery(() -> queryService.queryPaylogPage(player, direction, query, limit, offset));
    }

    public QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, int limit, int offset) {
        initialize();
        return readQuery(() -> queryService.queryDealHistoryPage(player, query, limit, offset));
    }

    public QueryPage<TransactionEntry> queryAvailablePaylogs(String payer, String receiver, String query, int limit, int offset) {
        initialize();
        return readQuery(() -> queryService.queryAvailablePaylogs(payer, receiver, query, limit, offset));
    }

    public QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, boolean includeArchived,
                                                                      DealHistorySort sort, int limit, int offset) {
        initialize();
        return readQuery(() -> queryService.queryDealHistoryPage(player, query, includeArchived, sort, limit, offset));
    }

    public List<CreditEntry> queryDealHistory(String player, String query, int limit, int offset) {
        initialize();
        return readQuery(() -> queryService.queryDealHistory(player, query, limit, offset));
    }

    public QueryPage<CreditEventEntry> queryCreditEventPage(String player, UUID creditId, int limit, int offset) {
        initialize();
        return readQuery(() -> executeQueryWithSchemaRetry("Historien-Ereignisse konnten nicht aus der Datenbank geladen werden.", () -> {
            int boundedLimit = Math.max(1, Math.min(PAGE_SIZE, limit));
            int boundedOffset = Math.max(0, offset);
            String normalizedPlayer = safe(player).trim().toLowerCase(Locale.ROOT);
            List<Object> parameters = new ArrayList<>();
            StringBuilder where = new StringBuilder(" WHERE 1=1");
            if (!normalizedPlayer.isBlank()) {
                where.append(" AND (LOWER(creditor)=? OR LOWER(debtor)=?)");
                parameters.add(normalizedPlayer);
                parameters.add(normalizedPlayer);
            }
            if (creditId != null) {
                where.append(" AND credit_id=?");
                parameters.add(creditId.toString());
            }
            String eventSource = creditId != null ? "credit_events USE INDEX (idx_events_credit_created)"
                    : normalizedPlayer.isBlank() ? "credit_events USE INDEX (idx_events_created_id)" : "credit_events";
            String eventOrder = creditId == null ? "created_at DESC,id DESC" : "credit_id,created_at DESC,id DESC";
            try (Connection connection = connection()) {
                beginConsistentRead(connection);
                long totalCount;
                if (normalizedPlayer.isBlank()) {
                    String summarySql = creditId == null
                            ? "SELECT COALESCE(SUM(event_count),0) FROM credit_event_counts"
                            : "SELECT event_count FROM credit_event_counts WHERE credit_id=?";
                    try (PreparedStatement count = connection.prepareStatement(summarySql)) {
                        if (creditId != null) count.setString(1, creditId.toString());
                        try (ResultSet result = count.executeQuery()) {
                            totalCount = result.next() ? result.getLong(1) : 0L;
                        }
                    }
                } else {
                    try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + eventSource + where)) {
                        for (int index = 0; index < parameters.size(); index++) count.setObject(index + 1, parameters.get(index));
                        try (ResultSet result = count.executeQuery()) {
                            result.next();
                            totalCount = result.getLong(1);
                        }
                    }
                }
                List<CreditEventEntry> entries = new ArrayList<>(boundedLimit);
                try (PreparedStatement page = connection.prepareStatement("SELECT * FROM " + eventSource + where + " ORDER BY " + eventOrder + " LIMIT ? OFFSET ?")) {
                    for (int index = 0; index < parameters.size(); index++) page.setObject(index + 1, parameters.get(index));
                    page.setInt(parameters.size() + 1, boundedLimit);
                    page.setInt(parameters.size() + 2, boundedOffset);
                    try (ResultSet result = page.executeQuery()) {
                        while (result.next()) entries.add(readEvent(result));
                    }
                }
                return new QueryPage<>(List.copyOf(entries), totalCount, boundedOffset, boundedLimit);
            }
        }));
    }

    public synchronized List<DataHealthRecord> runHealthCheck() { return healthInspectionService.runHealthCheck(); }
    public synchronized List<DataHealthRecord> listHealthRecords(boolean includeResolved) { return healthInspectionService.listHealthRecords(includeResolved); }
    int lastHealthInspectionQueryCount() { return healthInspectionService.lastInspectionQueryCount(); }
    public synchronized boolean resolveHealthRecord(UUID id, String repairPayload, boolean ignored) { return healthInspectionService.resolveHealthRecord(id, repairPayload, ignored); }
    public synchronized boolean repairCredit(CreditEntry replacement, String reason) { return logicalRepairService.repairCredit(replacement, reason); }
    public synchronized boolean repairPayment(Payment replacement, String reason) { return logicalRepairService.repairPayment(replacement, reason); }
    public synchronized boolean repairEvent(CreditEventEntry replacement, String reason) { return logicalRepairService.repairEvent(replacement, reason); }
    public synchronized boolean discardRecoveryRecord(DiscardRecordType type, UUID id, String reason, boolean confirmed) {
        if (!confirmed || type == null || id == null) return false;
        Path snapshot = createDiscardSafetySnapshot(type, id);
        return snapshot != null && logicalRepairService.discard(type, id, reason, snapshot);
    }

    public StatisticsEventSlice queryStatisticsEvents(String player, long fromInclusive, long toInclusive) {
        initialize();
        return readQuery(() -> executeQueryWithSchemaRetry("Statistik-Ereignisse konnten nicht aus der Datenbank geladen werden.", () -> {
            String normalizedPlayer = safe(player).toLowerCase(Locale.ROOT);
            if (normalizedPlayer.isBlank() || toInclusive < fromInclusive) return new StatisticsEventSlice(List.of(), Set.of());
            try (Connection connection = connection()) {
                beginConsistentRead(connection);
                List<CreditEventEntry> matching = new ArrayList<>();
                String rangeSql = "SELECT * FROM credit_events WHERE created_at>=? AND created_at<=? AND (LOWER(creditor)=? OR LOWER(debtor)=?) ORDER BY created_at,id";
                try (PreparedStatement statement = connection.prepareStatement(rangeSql)) {
                    statement.setLong(1, Math.max(0L, fromInclusive));
                    statement.setLong(2, toInclusive);
                    statement.setString(3, normalizedPlayer);
                    statement.setString(4, normalizedPlayer);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) matching.add(readEvent(result));
                    }
                }
                Set<UUID> inactive = new HashSet<>();
                String stateSql = "SELECT credit_id,event_type FROM (SELECT credit_id,event_type,ROW_NUMBER() OVER(PARTITION BY credit_id ORDER BY created_at DESC,id DESC) row_number FROM credit_events WHERE created_at<=? AND (LOWER(creditor)=? OR LOWER(debtor)=?) AND event_type IN ('CREDIT_DELETED','CREDIT_ARCHIVED','CREDIT_REACTIVATED')) latest WHERE row_number=1 AND event_type IN ('CREDIT_DELETED','CREDIT_ARCHIVED')";
                try (PreparedStatement statement = connection.prepareStatement(stateSql)) {
                    statement.setLong(1, toInclusive);
                    statement.setString(2, normalizedPlayer);
                    statement.setString(3, normalizedPlayer);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) inactive.add(UUID.fromString(result.getString("credit_id")));
                    }
                }
                return new StatisticsEventSlice(List.copyOf(matching), Set.copyOf(inactive));
            }
        }));
    }

    private Path createDiscardSafetySnapshot(DiscardRecordType type, UUID id) {
        initialize();
        if (isPhysicalRecoveryState() || !Files.isRegularFile(FileManager.getDatabaseStorageFile())) return null;
        long createdAt = System.currentTimeMillis();
        Path directory = FileManager.getDiscardRecoveryDirectory();
        Path target = directory.resolve("creditmanager_before_" + type.name().toLowerCase(Locale.ROOT) + "_discard_" + createdAt + '_' + UUID.randomUUID() + ".zip");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            recoveryFileOps.createDirectories(directory);
            statement.execute("BACKUP TO '" + escapeSqlLiteral(target.toAbsolutePath().normalize().toString()) + "'");
            BackupValidation validation = validateRecoverySnapshot(target);
            if (!validation.valid() || validation.schemaVersion() != SCHEMA_VERSION) {
                recoveryFileOps.deleteIfExists(target);
                return null;
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("status", "VALIDATED");
            manifest.put("record_type", type.name());
            manifest.put("record_id", id.toString());
            manifest.put("created_at", createdAt);
            manifest.put("schema_version", validation.schemaVersion());
            manifest.put("revision", validation.revision());
            manifest.put("credit_count", validation.creditCount());
            manifest.put("payment_count", validation.paymentCount());
            manifest.put("paylog_count", validation.paylogCount());
            manifest.put("event_count", validation.eventCount());
            manifest.put("sha256", sha256File(target));
            manifest.put("size", fileSize(target));
            Path manifestFile = target.resolveSibling(target.getFileName() + ".manifest.json");
            Path temporary = manifestFile.resolveSibling(manifestFile.getFileName() + ".tmp");
            recoveryFileOps.writeString(temporary, GSON.toJson(manifest), StandardCharsets.UTF_8);
            recoveryFileOps.moveReplacing(temporary, manifestFile);
            return target;
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not create validated safety snapshot before explicit recovery discard", exception);
            return null;
        }
    }

    void validateState(Connection connection, Collection<CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) throws SQLException {
        Set<UUID> creditIds = new HashSet<>();
        Map<UUID, Long> paymentTotals = new LinkedHashMap<>();
        for (CreditEntry credit : credits) {
            if (!isValidCredit(credit) || !creditIds.add(credit.getId())) throw new SQLException("Invalid or duplicate credit");
        }
        Set<UUID> paymentIds = new HashSet<>();
        for (Payment payment : payments) {
            if (!isValidPayment(payment) || !creditIds.contains(payment.getCreditId()) || !paymentIds.add(payment.getId())) throw new SQLException("Invalid, duplicate or orphan payment");
            paymentTotals.merge(payment.getCreditId(), payment.getAmountMinor(), Math::addExact);
            validatePaylogPaymentLink(connection, payment);
        }
        for (CreditEntry credit : credits) validateDerivedCreditState(credit, paymentTotals.getOrDefault(credit.getId(), 0L));
        validatePaylogLinks(connection, payments);
        Set<UUID> eventIds = new HashSet<>();
        for (CreditEventEntry event : events) {
            if (!isValidEvent(event) || !creditIds.contains(event.getCreditId()) || !eventIds.add(event.getId())) throw new SQLException("Invalid, duplicate or orphan event");
        }
    }

    void validateDerivedCreditState(CreditEntry credit, long paymentTotal) throws SQLException {
        if (credit.getPaidAmountMinor() != paymentTotal) {
            throw new SQLException("Credit paid amount does not match its payments");
        }
        if (CreditStatusRules.isManualFinal(credit.getStatus())) return;
        String expected = CreditStatusRules.derive(credit.getAmountMinor(), paymentTotal);
        if (!expected.equals(credit.getStatus())) throw new SQLException("Credit status does not match its payments");
    }

    private void validatePaylogLinks(Connection connection, Collection<Payment> payments) throws SQLException {
        Map<UUID, Long> linkedAmounts = new LinkedHashMap<>();
        for (Payment payment : payments) {
            if (payment.getPaylogId() != null) {
                linkedAmounts.merge(payment.getPaylogId(), payment.getAmountMinor(), Math::addExact);
            }
        }
        for (Map.Entry<UUID, Long> link : linkedAmounts.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT amount FROM paylogs WHERE id=?")) {
                statement.setString(1, link.getKey().toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Payment links unknown paylog " + link.getKey());
                    if (link.getValue() > result.getLong(1)) throw new SQLException("Linked payments exceed paylog amount");
                }
            }
        }
    }

    void validatePersistedPaylogLinks(Connection connection, Collection<Payment> payments) throws SQLException {
        Set<UUID> paylogIds = new HashSet<>();
        for (Payment payment : payments) if (payment.getPaylogId() != null) paylogIds.add(payment.getPaylogId());
        for (UUID paylogId : paylogIds) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT p.amount, COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=p.id), 0) FROM paylogs p WHERE p.id=?")) {
                statement.setString(1, paylogId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Payment links unknown paylog " + paylogId);
                    if (result.getLong(2) > result.getLong(1)) {
                        throw new SQLException("Linked payments exceed paylog amount");
                    }
                }
            }
        }
    }

    void validatePaylogPaymentLink(Connection connection, Payment payment) throws SQLException {
        if (payment.getPaylogId() == null) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT payer, receiver, amount FROM paylogs WHERE id=?")) {
            statement.setString(1, payment.getPaylogId().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Payment links unknown paylog " + payment.getPaylogId());
                if (!safe(payment.getFromPlayer()).equalsIgnoreCase(safe(result.getString("payer")))
                        || !safe(payment.getToPlayer()).equalsIgnoreCase(safe(result.getString("receiver")))) {
                    throw new SQLException("Payment direction does not match linked paylog");
                }
                if (!safe(payment.getSource()).startsWith("PAYLOG_")) throw new SQLException("Paylog-linked payment has invalid source");
                if (payment.getAmountMinor() > result.getLong("amount")) throw new SQLException("Payment exceeds linked paylog");
            }
        }
    }

    private boolean isValidCredit(CreditEntry value) { return value != null && value.getId() != null && CreditValidationRules.isValidPlayerName(value.getCreditor()) && CreditValidationRules.isValidPlayerName(value.getDebtor()) && !value.getCreditor().equalsIgnoreCase(value.getDebtor()) && MoneyRules.isPositive(value.getAmountMinor()) && value.getPaidAmountMinor() >= 0L && value.getPaidAmountMinor() <= value.getAmountMinor(); }
    private boolean isValidPayment(Payment value) { return value != null && value.getId() != null && value.getCreditId() != null && MoneyRules.isPositive(value.getAmountMinor()); }
    private boolean isValidEvent(CreditEventEntry value) { return value != null && value.getId() != null && value.getCreditId() != null && value.getType() != null && MoneyRules.isValid(value.getAmountMinor()); }
    private boolean isValidPaylog(TransactionEntry value) { return value != null && CreditValidationRules.isValidPlayerName(value.getFromPlayer()) && CreditValidationRules.isValidPlayerName(value.getToPlayer()) && MoneyRules.isPositive(value.getAmountMinor()) && value.getTimestamp() > 0 && safe(value.getRawText()).length() <= CreditValidationRules.MAX_PAYLOG_RAW_TEXT_LENGTH && safe(value.getMetadata()).length() <= CreditValidationRules.MAX_METADATA_LENGTH; }

    void upsertCredit(Connection connection, CreditEntry entry, long rowRevision) throws SQLException {
        Long completed = entry.getCompletedAt();
        boolean finalStatus = "PAID".equals(entry.getStatus()) || CreditStatusRules.isManualFinal(entry.getStatus());
        if (finalStatus && completed == null) completed = existingCompletedAt(connection, entry.getId());
        if (finalStatus && completed == null) completed = System.currentTimeMillis();
        if (!finalStatus) completed = null;
        entry.setCompletedAt(completed);
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO credits (id, deal_name, creditor, debtor, amount, paid_amount, created_at, due_date, status, note, search_text, completed_at, archived, revision) KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, entry.getId().toString()); statement.setString(2, entry.getDealName()); statement.setString(3, entry.getCreditor()); statement.setString(4, entry.getDebtor()); statement.setLong(5, entry.getAmountMinor()); statement.setLong(6, entry.getPaidAmountMinor()); statement.setLong(7, entry.getCreatedAt()); setNullableLong(statement, 8, entry.getDueDate()); statement.setString(9, entry.getStatus()); statement.setString(10, entry.getNote()); statement.setString(11, DealSearchText.build(entry)); setNullableLong(statement, 12, completed); statement.setBoolean(13, entry.isArchived()); statement.setLong(14, rowRevision); statement.executeUpdate();
        }
        schemaManager.replaceDealSearchTokens(connection, entry);
    }

    void upsertPayment(Connection connection, Payment payment, long rowRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO payments (id, credit_id, from_player, to_player, amount, payment_kind, items_json, item_nbt, item_nbt_entries, created_at, source, paylog_id, note, revision) KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, payment.getId().toString()); statement.setString(2, payment.getCreditId().toString()); statement.setString(3, payment.getFromPlayer()); statement.setString(4, payment.getToPlayer()); statement.setLong(5, payment.getAmountMinor()); statement.setString(6, payment.getPaymentKind().name()); statement.setString(7, GSON.toJson(payment.getItems())); statement.setString(8, payment.getItemNbt()); statement.setString(9, GSON.toJson(payment.getItemNbtEntries())); statement.setLong(10, payment.getTimestamp()); statement.setString(11, payment.getSource()); statement.setString(12, payment.getPaylogId() == null ? null : payment.getPaylogId().toString()); statement.setString(13, payment.getNote()); statement.setLong(14, rowRevision); statement.executeUpdate();
        }
    }

    UUID paymentPaylogId(Connection connection, UUID paymentId) throws SQLException {
        if (paymentId == null) return null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT paylog_id FROM payments WHERE id=?")) {
            statement.setString(1, paymentId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && validUuid(result.getString(1)) ? UUID.fromString(result.getString(1)) : null;
            }
        }
    }

    void refreshPaylogLinkAmounts(Connection connection, Collection<UUID> paylogIds) throws SQLException {
        if (paylogIds == null || paylogIds.isEmpty()) return;
        List<UUID> values = paylogIds.stream().filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < values.size(); start += ID_BATCH_SIZE) {
            int end = Math.min(values.size(), start + ID_BATCH_SIZE);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE paylogs SET linked_amount=COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=paylogs.id),0), link_count=COALESCE((SELECT COUNT(*) FROM payments WHERE paylog_id=paylogs.id),0) WHERE id=?")) {
                for (UUID paylogId : values.subList(start, end)) {
                    statement.setString(1, paylogId.toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }

    void upsertEvent(Connection connection, CreditEventEntry event, long rowRevision) throws SQLException {
        String previousCreditId = eventCreditId(connection, event.getId());
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO credit_events (id, credit_id, event_type, amount, paid_after, remaining_after, created_at, deal_name, creditor, debtor, note, amount_before, amount_after, actor, source, item_payment, revision) KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, event.getId().toString()); statement.setString(2, event.getCreditId().toString()); statement.setString(3, event.getType().name()); statement.setLong(4, event.getAmountMinor()); statement.setLong(5, event.getPaidAmountAfterMinor()); statement.setLong(6, event.getRemainingAmountAfterMinor()); statement.setLong(7, event.getTimestamp()); statement.setString(8, event.getDealName()); statement.setString(9, event.getCreditor()); statement.setString(10, event.getDebtor()); statement.setString(11, event.getNote()); statement.setLong(12, event.getAmountBeforeMinor()); statement.setLong(13, event.getAmountAfterMinor()); statement.setString(14, event.getActor()); statement.setString(15, event.getSource()); statement.setBoolean(16, event.isItemPayment()); statement.setLong(17, rowRevision); statement.executeUpdate();
        }
        String currentCreditId = event.getCreditId().toString();
        if (previousCreditId == null) adjustEventCount(connection, currentCreditId, 1L);
        else if (!previousCreditId.equals(currentCreditId)) {
            adjustEventCount(connection, previousCreditId, -1L);
            adjustEventCount(connection, currentCreditId, 1L);
        }
    }

    void rebuildEventCounts(Connection connection) throws SQLException {
        schemaManager.rebuildEventCounts(connection);
    }

    private String eventCreditId(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT credit_id FROM credit_events WHERE id=?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void adjustEventCount(Connection connection, String creditId, long delta) throws SQLException {
        if (delta > 0L) {
            try (PreparedStatement update = connection.prepareStatement("UPDATE credit_event_counts SET event_count=event_count+1 WHERE credit_id=?")) {
                update.setString(1, creditId);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO credit_event_counts (credit_id,event_count) VALUES (?,1)")) {
                        insert.setString(1, creditId);
                        insert.executeUpdate();
                    }
                }
            }
            return;
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE credit_event_counts SET event_count=CASE WHEN event_count>0 THEN event_count-1 ELSE 0 END WHERE credit_id=?")) {
            update.setString(1, creditId);
            update.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM credit_event_counts WHERE credit_id=? AND event_count=0")) {
            delete.setString(1, creditId);
            delete.executeUpdate();
        }
    }

    private boolean insertPaylog(Connection connection, TransactionEntry entry, boolean deduplicate, long rowRevision) throws SQLException {
        String normalized = entry.getNormalizedText();
        if (blank(normalized)) normalized = PaylogSearchText.build(entry);
        String hash = blank(entry.getHash()) ? paylogHash(entry, normalized) : entry.getHash();
        UUID id = entry.getId() == null ? UUID.randomUUID() : entry.getId();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO paylogs (id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, revision, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, id.toString()); statement.setString(2, entry.getFromPlayer()); statement.setString(3, entry.getToPlayer()); statement.setLong(4, entry.getAmountMinor()); statement.setString(5, entry.getRawText()); statement.setString(6, normalized); statement.setLong(7, entry.getTimestamp()); statement.setString(8, hash); statement.setString(9, blank(entry.getSource()) ? "DETECTED" : entry.getSource()); statement.setLong(10, rowRevision); statement.setString(11, entry.getMetadata()); statement.executeUpdate();
            entry.setId(id); entry.setNormalizedText(normalized); entry.setHash(hash); schemaManager.replacePaylogSearchTokens(connection, entry); return true;
        } catch (SQLException exception) {
            if (deduplicate && "23505".equals(exception.getSQLState())) return false;
            throw exception;
        }
    }

    private String paylogHash(TransactionEntry entry, String normalized) {
        try {
            String canonical = safe(entry.getSource()) + '\u0000' + safe(entry.getFromPlayer()).toLowerCase(Locale.ROOT) + '\u0000' + safe(entry.getToPlayer()).toLowerCase(Locale.ROOT) + '\u0000' + entry.getAmountMinor() + '\u0000' + safe(entry.getRawText()) + '\u0000' + normalized + '\u0000' + entry.getTimestamp();
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    List<CreditEntry> readCredits(Connection connection, String sql, List<String> values, Object... page) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindStrings(statement, values);
            for (int index = 0; index < page.length; index++) statement.setObject(values.size() + index + 1, page[index]);
            try (ResultSet result = statement.executeQuery()) {
                List<CreditEntry> entries = new ArrayList<>();
                while (result.next()) {
                    CreditEntry entry = new CreditEntry();
                    entry.setId(UUID.fromString(result.getString("id"))); entry.setDealName(result.getString("deal_name")); entry.setCreditor(result.getString("creditor")); entry.setDebtor(result.getString("debtor")); entry.setAmountMinor(result.getLong("amount")); entry.setPaidAmountMinor(result.getLong("paid_amount")); entry.setCreatedAt(result.getLong("created_at")); long due = result.getLong("due_date"); entry.setDueDate(result.wasNull() ? null : due); entry.setStatus(result.getString("status")); entry.setNote(result.getString("note")); long completed = result.getLong("completed_at"); entry.setCompletedAt(result.wasNull() ? null : completed); entry.setArchived(result.getBoolean("archived")); entry.setPayments(new ArrayList<>()); entries.add(entry);
                }
                return entries;
            }
        }
    }

    private List<Payment> loadPayments(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM payments"); ResultSet result = statement.executeQuery()) {
            List<Payment> entries = new ArrayList<>();
            while (result.next()) {
                Payment payment = new Payment(UUID.fromString(result.getString("credit_id")), result.getString("from_player"), result.getString("to_player"), result.getLong("amount"), strictStringList(result.getString("items_json"), "payments", result.getString("id"), "items_json"), result.getString("source"));
                payment.setId(UUID.fromString(result.getString("id"))); payment.setPaymentKind(PaymentKind.valueOf(result.getString("payment_kind"))); payment.setItemNbt(result.getString("item_nbt")); payment.setItemNbtEntries(strictStringList(result.getString("item_nbt_entries"), "payments", result.getString("id"), "item_nbt_entries")); payment.setTimestamp(result.getLong("created_at")); String paylogId = result.getString("paylog_id"); if (validUuid(paylogId)) payment.setPaylogId(UUID.fromString(paylogId)); payment.setNote(result.getString("note")); entries.add(payment);
            }
            return entries;
        }
    }

    List<Payment> loadPaymentsForCredit(Connection connection, UUID creditId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM payments WHERE credit_id=?")) {
            statement.setString(1, creditId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<Payment> entries = new ArrayList<>();
                while (result.next()) {
                    Payment payment = new Payment(UUID.fromString(result.getString("credit_id")), result.getString("from_player"), result.getString("to_player"), result.getLong("amount"), strictStringList(result.getString("items_json"), "payments", result.getString("id"), "items_json"), result.getString("source"));
                    payment.setId(UUID.fromString(result.getString("id")));
                    payment.setPaymentKind(PaymentKind.valueOf(result.getString("payment_kind")));
                    payment.setItemNbt(result.getString("item_nbt"));
                    payment.setItemNbtEntries(strictStringList(result.getString("item_nbt_entries"), "payments", result.getString("id"), "item_nbt_entries"));
                    payment.setTimestamp(result.getLong("created_at"));
                    String paylogId = result.getString("paylog_id");
                    if (validUuid(paylogId)) payment.setPaylogId(UUID.fromString(paylogId));
                    payment.setNote(result.getString("note"));
                    entries.add(payment);
                }
                return entries;
            }
        }
    }

    private List<CreditEventEntry> loadEvents(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM credit_events"); ResultSet result = statement.executeQuery()) {
            List<CreditEventEntry> entries = new ArrayList<>();
            while (result.next()) {
                entries.add(readEvent(result));
            }
            return entries;
        }
    }

    TransactionEntry readPaylog(ResultSet result) throws SQLException {
        TransactionEntry entry = new TransactionEntry();
        entry.setId(UUID.fromString(result.getString("id"))); entry.setFromPlayer(result.getString("payer")); entry.setToPlayer(result.getString("receiver")); entry.setAmountMinor(result.getLong("amount")); entry.setRawText(result.getString("raw_text")); entry.setNormalizedText(result.getString("normalized_text")); entry.setTimestamp(result.getLong("created_at")); entry.setHash(result.getString("entry_hash")); entry.setSource(result.getString("source")); entry.setMetadata(result.getString("metadata"));
        entry.setLinkedAmountMinor(result.getLong("linked_amount"));
        return entry;
    }

    private Long existingCompletedAt(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT completed_at FROM credits WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) { if (!result.next()) return null; long value = result.getLong(1); return result.wasNull() ? null : value; }
        }
    }

    private boolean rowExists(Connection connection, String table, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void preserveLegacyRecord(Connection connection, LegacyRecord record, String migrationId, int[] counts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO legacy_records (id, record_kind, original_id, raw_payload, reason, created_at, migration_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, record.kind());
            statement.setString(3, record.originalId());
            statement.setString(4, record.rawPayload());
            statement.setString(5, record.reason());
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, migrationId);
            statement.executeUpdate();
            counts[4]++;
        }
    }

    private boolean sameCredit(Connection connection, CreditEntry credit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT deal_name, creditor, debtor, amount, paid_amount, created_at, due_date, status, note, archived FROM credits WHERE id=?")) {
            statement.setString(1, credit.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                long dueDate = result.getLong("due_date");
                Long storedDueDate = result.wasNull() ? null : dueDate;
                return Objects.equals(result.getString("deal_name"), credit.getDealName())
                        && Objects.equals(result.getString("creditor"), credit.getCreditor())
                        && Objects.equals(result.getString("debtor"), credit.getDebtor())
                        && result.getLong("amount") == credit.getAmountMinor()
                        && result.getLong("paid_amount") == credit.getPaidAmountMinor()
                        && result.getLong("created_at") == credit.getCreatedAt()
                        && Objects.equals(storedDueDate, credit.getDueDate())
                        && Objects.equals(result.getString("status"), credit.getStatus())
                        && Objects.equals(result.getString("note"), credit.getNote())
                        && result.getBoolean("archived") == credit.isArchived();
            }
        }
    }

    private boolean samePayment(Connection connection, Payment payment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT credit_id, from_player, to_player, amount, payment_kind, items_json, item_nbt, item_nbt_entries, created_at, source, paylog_id, note FROM payments WHERE id=?")) {
            statement.setString(1, payment.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && Objects.equals(result.getString("credit_id"), payment.getCreditId().toString())
                        && Objects.equals(result.getString("from_player"), payment.getFromPlayer())
                        && Objects.equals(result.getString("to_player"), payment.getToPlayer())
                        && result.getLong("amount") == payment.getAmountMinor()
                        && Objects.equals(result.getString("payment_kind"), payment.getPaymentKind().name())
                        && Objects.equals(result.getString("items_json"), GSON.toJson(payment.getItems()))
                        && Objects.equals(result.getString("item_nbt"), payment.getItemNbt())
                        && Objects.equals(result.getString("item_nbt_entries"), GSON.toJson(payment.getItemNbtEntries()))
                        && result.getLong("created_at") == payment.getTimestamp()
                        && Objects.equals(result.getString("source"), payment.getSource())
                        && Objects.equals(result.getString("paylog_id"), payment.getPaylogId() == null ? null : payment.getPaylogId().toString())
                        && Objects.equals(result.getString("note"), payment.getNote());
            }
        }
    }

    private boolean sameEvent(Connection connection, CreditEventEntry event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT credit_id, event_type, amount, paid_after, remaining_after, created_at, deal_name, creditor, debtor, note, amount_before, amount_after, actor, source, item_payment FROM credit_events WHERE id=?")) {
            statement.setString(1, event.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && Objects.equals(result.getString("credit_id"), event.getCreditId().toString())
                        && Objects.equals(result.getString("event_type"), event.getType().name())
                        && result.getLong("amount") == event.getAmountMinor()
                        && result.getLong("paid_after") == event.getPaidAmountAfterMinor()
                        && result.getLong("remaining_after") == event.getRemainingAmountAfterMinor()
                        && result.getLong("created_at") == event.getTimestamp()
                        && Objects.equals(result.getString("deal_name"), event.getDealName())
                        && Objects.equals(result.getString("creditor"), event.getCreditor())
                        && Objects.equals(result.getString("debtor"), event.getDebtor())
                        && Objects.equals(result.getString("note"), event.getNote())
                        && result.getLong("amount_before") == event.getAmountBeforeMinor()
                        && result.getLong("amount_after") == event.getAmountAfterMinor()
                        && Objects.equals(result.getString("actor"), event.getActor())
                        && Objects.equals(result.getString("source"), event.getSource())
                        && result.getBoolean("item_payment") == event.isItemPayment();
            }
        }
    }

    private boolean samePaylog(Connection connection, TransactionEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT payer, receiver, amount, raw_text, created_at, source, metadata FROM paylogs WHERE id=?")) {
            statement.setString(1, entry.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && Objects.equals(result.getString("payer"), entry.getFromPlayer())
                        && Objects.equals(result.getString("receiver"), entry.getToPlayer())
                        && result.getLong("amount") == entry.getAmountMinor()
                        && Objects.equals(result.getString("raw_text"), entry.getRawText())
                        && result.getLong("created_at") == entry.getTimestamp()
                        && Objects.equals(result.getString("source"), entry.getSource())
                        && Objects.equals(result.getString("metadata"), entry.getMetadata());
            }
        }
    }

    boolean hasDomainData(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM credits) + (SELECT COUNT(*) FROM payments) + (SELECT COUNT(*) FROM credit_events) + (SELECT COUNT(*) FROM paylogs)")) { result.next(); return result.getLong(1) > 0; }
    }

    boolean hasOpenHealthErrors(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM data_health_records WHERE status='OPEN' AND severity='ERROR' LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    boolean inTransaction(DatabaseWork work) {
        return inTransaction(DatabaseWriteMode.NORMAL, work);
    }

    private CreditEventEntry readEvent(ResultSet result) throws SQLException {
        CreditEventEntry event = new CreditEventEntry();
        event.setId(UUID.fromString(result.getString("id")));
        event.setCreditId(UUID.fromString(result.getString("credit_id")));
        event.setType(CreditEventType.valueOf(result.getString("event_type")));
        event.setAmountMinor(result.getLong("amount"));
        event.setPaidAmountAfterMinor(result.getLong("paid_after"));
        event.setRemainingAmountAfterMinor(result.getLong("remaining_after"));
        event.setTimestamp(result.getLong("created_at"));
        event.setDealName(result.getString("deal_name"));
        event.setCreditor(result.getString("creditor"));
        event.setDebtor(result.getString("debtor"));
        event.setNote(result.getString("note"));
        event.setAmountBeforeMinor(result.getLong("amount_before"));
        event.setAmountAfterMinor(result.getLong("amount_after"));
        event.setActor(result.getString("actor"));
        event.setSource(result.getString("source"));
        event.setItemPayment(result.getBoolean("item_payment"));
        return event;
    }

    boolean inTransaction(DatabaseWriteMode mode, DatabaseWork work) {
        initialize();
        lifecycleLock.readLock().lock();
        writerLock.lock();
        try (Connection connection = connection()) {
            boolean migrationIncomplete = mode == DatabaseWriteMode.NORMAL && !"COMPLETED".equals(metadata(connection, "json_migration_status"))
                    && hasLegacyJsonFiles();
            boolean openHealthError = hasOpenHealthErrors(connection);
            if (!writeGate.allows(mode, !isPhysicalRecoveryState(), writeLocked, openHealthError,
                    migrationIncomplete, backupProtectionState == BackupCheckpointService.ProtectionState.CRITICAL)) return false;
            connection.setAutoCommit(false);
            try { work.run(connection); connection.commit(); return true; }
            catch (DuplicatePaylogException ignored) { connection.rollback(); return false; }
            catch (Exception exception) {
                connection.rollback();
                if (isPhysicalH2Corruption(exception)) {
                    try { connection.close(); } catch (SQLException ignored) { }
                    handlePhysicalCorruption(exception, FileManager.getDatabaseFile().toAbsolutePath());
                }
                CreditManagerClient.LOGGER.warn("Local database transaction was rolled back", exception);
                return false;
            }
        } catch (SQLException exception) {
            if (isPhysicalH2Corruption(exception)) handlePhysicalCorruption(exception, FileManager.getDatabaseFile().toAbsolutePath());
            if (isMissingDatabase(exception)) {
                markUnavailable(DatabaseAvailability.MISSING_DATABASE,
                        "Die aktive CreditManager-Datenbank fehlt. Es wurde keine neue leere Datenbank erzeugt.",
                        FileManager.getDatabaseFile().toAbsolutePath());
            } else {
                healthy = false;
                writeLocked = true;
                if (!isPhysicalRecoveryState()) availability = DatabaseAvailability.WRITE_LOCKED;
            }
            CreditManagerClient.LOGGER.warn("Could not open local database transaction", exception);
            return false;
        } finally {
            writerLock.unlock();
            lifecycleLock.readLock().unlock();
        }
    }

    private List<BackupManifestEntry> discoverBackups() {
        Path directory = FileManager.getBackupDirectory().toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) return List.of();
        Map<String, BackupManifestEntry> indexed = new LinkedHashMap<>();
        for (BackupManifestEntry entry : readBackupManifest()) indexed.putIfAbsent(entry.fileName(), entry);
        List<BackupManifestEntry> discovered = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path candidate : files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".zip") || name.endsWith(".mv.db");
                    }).toList()) {
                String name = candidate.getFileName().toString();
                BackupManifestEntry old = indexed.get(name);
                boolean recoverySnapshotName = name.toLowerCase(Locale.ROOT).startsWith("creditmanager_recovery_snapshot_");
                BackupArtifactType artifactType = recoverySnapshotName || old != null && old.artifactType() == BackupArtifactType.RECOVERY_SNAPSHOT
                        ? BackupArtifactType.RECOVERY_SNAPSHOT : BackupArtifactType.RESTORABLE_HEALTHY;
                BackupValidation validation = name.toLowerCase(Locale.ROOT).endsWith(".zip")
                        ? artifactType == BackupArtifactType.RECOVERY_SNAPSHOT ? validateRecoverySnapshot(candidate) : validateBackup(candidate)
                        : validateLegacyBackupCandidate(candidate);
                if (!validation.valid()) continue;
                String checksum = sha256File(candidate);
                long size = fileSize(candidate);
                boolean trustedMetadata = old != null && !blank(old.sha256()) && old.sha256().equals(checksum) && old.size() == size;
                long createdAt = !trustedMetadata || old.createdAt() <= 0L ? modifiedAt(candidate) : old.createdAt();
                String format = artifactType == BackupArtifactType.RECOVERY_SNAPSHOT ? "h2-recovery-snapshot"
                        : name.toLowerCase(Locale.ROOT).endsWith(".zip") ? "h2-backup-zip" : "legacy-h2";
                boolean healthyArtifact = artifactType == BackupArtifactType.RESTORABLE_HEALTHY
                        && !blank(validation.storageUuid()) && validation.generation() > 0L
                        && (!trustedMetadata || old.healthy());
                boolean restoreEligible = healthyArtifact && (!trustedMetadata || old.automaticRestoreEligible());
                discovered.add(new BackupManifestEntry(name, createdAt, validation.schemaVersion(), validation.revision(),
                        validation.creditCount(), validation.paymentCount(), validation.paylogCount(), validation.eventCount(),
                        healthyArtifact, format, checksum, size, 3, artifactType, restoreEligible));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("CreditManager backup catalog could not be discovered", exception);
        }
        discovered.sort(Comparator.comparingLong(BackupManifestEntry::createdAt).reversed());
        List<BackupManifestEntry> retained = retainedBackups(discovered);
        archiveExcessBackups(discovered, retained);
        try {
            writeBackupManifest(retained);
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not rebuild CreditManager backup manifest", exception);
        }
        return List.copyOf(retained);
    }

    private List<BackupCandidate> discoverBackupCandidates() {
        List<BackupCandidate> candidates = new ArrayList<>();
        for (BackupManifestEntry entry : discoverBackups()) {
            Path path = safeBackupPath(entry.fileName());
            if (path == null || !Files.isRegularFile(path)) continue;
            BackupValidation validation = entry.artifactType() == BackupArtifactType.RECOVERY_SNAPSHOT
                    ? validateRecoverySnapshot(path)
                    : path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")
                    ? validateBackup(path) : validateLegacyBackupCandidate(path);
            candidates.add(new BackupCandidate(entry, path, BackupSource.LOCAL,
                    validation.storageUuid(), validation.generation()));
        }
        candidates.addAll(discoverMirrorBackups());
        return List.copyOf(candidates);
    }

    private List<BackupCandidate> discoverMirrorBackups() {
        BackupMirrorService mirror = mirrorService();
        if (!mirror.enabled()) return List.of();
        Map<String, BackupMirrorService.MirrorBackupEntry> indexed = new LinkedHashMap<>();
        for (BackupMirrorService.MirrorBackupEntry entry : mirror.readManifest().entries()) indexed.putIfAbsent(entry.filename(), entry);
        List<BackupCandidate> discovered = new ArrayList<>();
        List<BackupMirrorService.MirrorBackupEntry> rebuilt = new ArrayList<>();
        for (Path candidate : mirror.candidateFiles()) {
            BackupValidation validation = validateBackup(candidate);
            if (!validation.valid() || validation.schemaVersion() != SCHEMA_VERSION || blank(validation.storageUuid())
                    || validation.generation() <= 0L) continue;
            String name = candidate.getFileName().toString();
            String checksum = sha256File(candidate);
            long size = fileSize(candidate);
            BackupMirrorService.MirrorBackupEntry old = indexed.get(name);
            boolean trusted = old != null && checksum.equals(old.sha256()) && size == old.size()
                    && validation.storageUuid().equals(old.storageUuid()) && validation.generation() == old.generation();
            long createdAt = trusted && old.createdAt() > 0L ? old.createdAt() : modifiedAt(candidate);
            BackupManifestEntry entry = new BackupManifestEntry(name, createdAt, validation.schemaVersion(), validation.revision(),
                    validation.creditCount(), validation.paymentCount(), validation.paylogCount(), validation.eventCount(),
                    true, "h2-backup-mirror", checksum, size, 3, BackupArtifactType.RESTORABLE_HEALTHY, true);
            BackupMirrorService.MirrorBackupEntry mirrorEntry = new BackupMirrorService.MirrorBackupEntry(name, createdAt,
                    validation.revision(), validation.schemaVersion(), validation.storageUuid(), validation.generation(),
                    checksum, size, validation.creditCount(), validation.paymentCount(), validation.paylogCount(),
                    validation.eventCount(), BackupArtifactType.RESTORABLE_HEALTHY, true);
            rebuilt.add(mirrorEntry);
            discovered.add(new BackupCandidate(entry, candidate, BackupSource.MIRROR,
                    mirrorEntry.storageUuid(), mirrorEntry.generation()));
        }
        try {
            mirror.rebuildManifest(rebuilt);
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not rebuild CreditManager mirror manifest", exception);
        }
        discovered.sort(Comparator.comparingLong((BackupCandidate value) -> value.entry().revision()).reversed()
                .thenComparing(Comparator.comparingLong((BackupCandidate value) -> value.entry().createdAt()).reversed()));
        return List.copyOf(discovered);
    }

    private List<BackupManifestEntry> retainedBackups(List<BackupManifestEntry> discovered) {
        if (discovered.size() <= MAX_ACTIVE_BACKUPS) return List.copyOf(discovered);
        List<BackupManifestEntry> retained = new ArrayList<>();
        discovered.stream().filter(BackupManifestEntry::automaticRestoreEligible).findFirst().ifPresent(retained::add);
        discovered.stream().filter(entry -> entry.artifactType() == BackupArtifactType.RECOVERY_SNAPSHOT)
                .findFirst().filter(entry -> !retained.contains(entry)).ifPresent(retained::add);
        for (BackupManifestEntry entry : discovered) {
            if (retained.size() >= MAX_ACTIVE_BACKUPS) break;
            if (!retained.contains(entry)) retained.add(entry);
        }
        retained.sort(Comparator.comparingLong(BackupManifestEntry::createdAt).reversed());
        return List.copyOf(retained);
    }

    private void archiveExcessBackups(List<BackupManifestEntry> discovered, List<BackupManifestEntry> retained) {
        Set<String> retainedNames = retained.stream().map(BackupManifestEntry::fileName).collect(java.util.stream.Collectors.toSet());
        Path archiveDirectory = FileManager.getRecoveryDirectory().resolve("retired-backups");
        for (BackupManifestEntry entry : discovered) {
            if (retainedNames.contains(entry.fileName())) continue;
            Path source = safeBackupPath(entry.fileName());
            if (source == null || !Files.isRegularFile(source)) continue;
            Path target = archiveDirectory.resolve(entry.createdAt() + "-" + entry.fileName());
            try {
                moveWithoutReplacing(source, target);
            } catch (IOException exception) {
                CreditManagerClient.LOGGER.warn("Could not archive CreditManager backup outside active retention: {}", source, exception);
            }
        }
        pruneRetiredBackups(archiveDirectory);
    }

    private void pruneRetiredBackups(Path archiveDirectory) {
        if (!Files.isDirectory(archiveDirectory)) return;
        try (var files = Files.list(archiveDirectory)) {
            List<Path> candidates = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".zip") || name.endsWith(".mv.db");
                    })
                    .sorted(Comparator.comparingLong(this::modifiedAt).reversed())
                    .toList();
            Path protectedHealthy = null;
            Path protectedSnapshot = null;
            for (Path candidate : candidates) {
                String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean snapshot = name.contains("recovery_snapshot");
                BackupValidation validation = name.endsWith(".zip")
                        ? snapshot ? validateRecoverySnapshot(candidate) : validateBackup(candidate)
                        : validateLegacyBackupCandidate(candidate);
                if (validation.valid() && snapshot && protectedSnapshot == null) protectedSnapshot = candidate;
                if (validation.valid() && !snapshot && protectedHealthy == null) {
                    protectedHealthy = candidate;
                }
                if (protectedHealthy != null && protectedSnapshot != null) break;
            }
            List<Path> retentionOrder = new ArrayList<>();
            if (protectedHealthy != null) retentionOrder.add(protectedHealthy);
            if (protectedSnapshot != null && !protectedSnapshot.equals(protectedHealthy)) retentionOrder.add(protectedSnapshot);
            for (Path candidate : candidates) {
                if (!candidate.equals(protectedHealthy) && !candidate.equals(protectedSnapshot)) retentionOrder.add(candidate);
            }
            int retainedCount = 0;
            long retainedBytes = 0L;
            for (Path candidate : retentionOrder) {
                long size = fileSize(candidate);
                boolean protectedArtifact = candidate.equals(protectedHealthy) || candidate.equals(protectedSnapshot);
                boolean withinLimits = retainedCount < MAX_RETIRED_BACKUPS
                        && (retainedCount == 0 || retainedBytes <= MAX_RETIRED_BACKUP_BYTES - Math.min(size, MAX_RETIRED_BACKUP_BYTES));
                if (protectedArtifact || withinLimits) {
                    retainedCount++;
                    retainedBytes = size > Long.MAX_VALUE - retainedBytes ? Long.MAX_VALUE : retainedBytes + size;
                } else {
                    recoveryFileOps.deleteIfExists(candidate);
                }
            }
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not enforce retired CreditManager backup retention", exception);
        }
    }

    private Path safeBackupPath(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        Path relative;
        try {
            relative = Path.of(fileName);
        } catch (RuntimeException exception) {
            return null;
        }
        if (relative.isAbsolute() || relative.getNameCount() != 1 || !relative.getFileName().toString().equals(fileName)) return null;
        Path directory = FileManager.getBackupDirectory().toAbsolutePath().normalize();
        Path resolved = directory.resolve(relative).normalize();
        return resolved.getParent() != null && resolved.getParent().equals(directory) ? resolved : null;
    }

    private String sha256File(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) { throw new IllegalStateException("Backup checksum could not be calculated: " + path, exception); }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) { throw new IllegalStateException("Backup size could not be read: " + path, exception); }
    }

    private <T> T readQuery(Supplier<T> query) {
        lifecycleLock.readLock().lock();
        try {
            return query.get();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    void inject(DatabaseFaultInjector.FailurePoint point) throws Exception {
        faultInjector.hit(point);
    }

    private boolean hasLegacyJsonFiles() {
        return Files.isRegularFile(FileManager.getCreditStateFile()) || Files.isRegularFile(FileManager.getCreditsFile())
                || Files.isRegularFile(FileManager.getPaymentsFile()) || Files.isRegularFile(FileManager.getTransactionsFile())
                || Files.isRegularFile(FileManager.getCreditEventsFile());
    }

    Connection connection() throws SQLException {
        if (isPhysicalRecoveryState()) throw new SQLException("CreditManager database is quarantined pending user recovery", "08006", 90030);
        if (!FileManager.databaseAccessAllowed()) throw new SQLException("CreditManager storage lease is not held", "55000");
        if (!Files.isRegularFile(FileManager.getDatabaseStorageFile())) {
            healthy = false;
            writeLocked = true;
            availability = DatabaseAvailability.MISSING_DATABASE;
            DataHealth.reportRecoveryRequired("Die aktive CreditManager-Datenbank fehlt. Es wurde keine neue leere Datenbank erzeugt.");
            throw new SQLException("Existing database file is missing", "90013");
        }
        return openConnection(FileManager.getDatabaseFile());
    }
    private Connection openConnection(Path databaseBase) throws SQLException { return connections.openExistingReadWrite(databaseBase); }
    <T> T executeQueryWithSchemaRetry(String message, SqlQuery<T> query) {
        try {
            return query.run();
        } catch (SQLException firstFailure) {
            if (isSchemaDrift(firstFailure) && recheckAndRepair()) {
                try {
                    return query.run();
                } catch (SQLException retryFailure) {
                    throw queryFailure(message, retryFailure);
                }
            }
            throw queryFailure(message, firstFailure);
        }
    }

    private IllegalStateException queryFailure(String message, SQLException exception) {
        if (isPhysicalH2Corruption(exception)) {
            if (!isPhysicalRecoveryState()) handlePhysicalCorruption(exception, FileManager.getDatabaseFile().toAbsolutePath());
            return new IllegalStateException("CreditManager-Datenbank ist physisch beschädigt. Recovery erforderlich.", exception);
        }
        if (isFatalStorageFailure(exception)) {
            healthy = false;
            writeLocked = true;
            DataHealth.reportRecoveryRequired(message + " Daten wurden nicht gelöscht; bitte die Wiederherstellung öffnen.");
            CreditManagerClient.LOGGER.error(message, exception);
            return new IllegalStateException(message + " Datenbankprüfung erforderlich.", exception);
        }
        CreditManagerClient.LOGGER.warn(message + " Die vorhandenen Daten bleiben unverändert sichtbar.", exception);
        return new IllegalStateException(message + " Neu laden oder Schema reparieren.", exception);
    }

    int installedSchemaVersion(Connection connection) throws SQLException { return schemaManager.installedSchemaVersion(connection); }

    private boolean isSchemaDrift(SQLException exception) {
        String state = safe(exception.getSQLState());
        int code = exception.getErrorCode();
        String message = safe(exception.getMessage()).toLowerCase(Locale.ROOT);
        return code == 42102 || code == 42122 || "42s02".equalsIgnoreCase(state) || "42s22".equalsIgnoreCase(state)
                || (message.contains("column") && message.contains("not found"))
                || (message.contains("table") && message.contains("not found"));
    }

    private boolean isFatalStorageFailure(SQLException exception) {
        String state = safe(exception.getSQLState());
        String message = safe(exception.getMessage()).toLowerCase(Locale.ROOT);
        return state.startsWith("08") || message.contains("database is closed")
                || (message.contains("file") && (message.contains("corrupt") || message.contains("read") || message.contains("access denied")));
    }
    long count(Connection connection, String sql, List<String> values) throws SQLException { try (PreparedStatement statement = connection.prepareStatement(sql)) { bindStrings(statement, values); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); } } }
    void bindStrings(PreparedStatement statement, List<String> values) throws SQLException { for (int index = 0; index < values.size(); index++) statement.setString(index + 1, values.get(index)); }
    long nextRevision(Connection connection) throws SQLException { return metadataDao.nextRevision(connection); }
    private long revision(Connection connection) throws SQLException { return metadataDao.revision(connection); }
    void bumpRevision(Connection connection) throws SQLException { metadataDao.bumpRevision(connection); }
    private String metadata(Connection connection, String key) throws SQLException { return metadataDao.read(connection, key); }
    private void putMetadata(Connection connection, String key, String value) throws SQLException { metadataDao.write(connection, key, value); }
    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException { if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value); }
    private List<String> strictStringList(String json, String table, String id, String column) throws SQLException {
        if (blank(json)) return List.of();
        try {
            List<String> values = GSON.fromJson(json, STRING_LIST);
            if (values == null || values.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("invalid string list");
            return List.copyOf(values);
        } catch (RuntimeException exception) {
            throw new SQLException("Malformed JSON in " + table + '.' + column + " for " + id, exception);
        }
    }
    void beginConsistentRead(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
    }
    String escapeLike(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
    private boolean validUuid(String value) { try { UUID.fromString(value); return true; } catch (RuntimeException ignored) { return false; } }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    String safe(String value) { return value == null ? "" : value; }
    private String escapeSqlLiteral(String value) { return value.replace("'", "''"); }
    String rowPayload(ResultSet result) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            var metadata = result.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                Object value = result.getObject(index);
                payload.put(metadata.getColumnLabel(index), value == null ? null : String.valueOf(value));
            }
            return GSON.toJson(payload);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private record BackupCreation(boolean successful, long revision, long createdAt, Path localArtifact,
                                  boolean mirrorSuccess, Path mirrorArtifact) {
        private static BackupCreation failed() { return new BackupCreation(false, -1L, 0L, null, false, null); }
    }
    private record BackupCandidate(BackupManifestEntry entry, Path path, BackupSource source,
                                   String storageUuid, long generation) { }
    private record BackupIdentity(String storageUuid, long generation) { }
    private record BackupIdentitySelection(boolean safe, String storageUuid, long generation) {
        private static BackupIdentitySelection safe(String storageUuid, long generation) {
            return new BackupIdentitySelection(true, storageUuid, generation);
        }
        private static BackupIdentitySelection unsafe() {
            return new BackupIdentitySelection(false, null, 0L);
        }
        private boolean matches(String candidateUuid, long candidateGeneration) {
            return safe && Objects.equals(storageUuid, candidateUuid) && generation == candidateGeneration;
        }
    }
    private record BackupValidation(boolean valid, int creditCount, int paymentCount, int paylogCount, int eventCount,
                                    int schemaVersion, long revision, String storageUuid, long generation) {
        private static BackupValidation invalid() { return new BackupValidation(false, 0, 0, 0, 0, 0, 0L, null, 0L); }
        private int domainCount() { return creditCount + paymentCount + paylogCount + eventCount; }
    }
    private record DatabaseFingerprint(String storageUuid, int schemaVersion, long revision, long[] domainCounts) { }
    DataHealthRecord readHealth(ResultSet result) throws SQLException { long resolved = result.getLong("resolved_at"); return new DataHealthRecord(UUID.fromString(result.getString("id")), result.getString("record_type"), result.getString("severity"), result.getString("source_table"), result.getString("source_id"), result.getString("title"), result.getString("message"), result.getString("raw_payload"), result.getString("repair_payload"), result.getString("status"), result.getLong("created_at"), result.wasNull() ? null : resolved); }
    @FunctionalInterface interface DatabaseWork { void run(Connection connection) throws Exception; }
    @FunctionalInterface interface SqlQuery<T> { T run() throws SQLException; }
    private static final class DuplicatePaylogException extends RuntimeException { }
}
