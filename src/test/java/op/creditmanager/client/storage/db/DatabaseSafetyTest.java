package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.config.ClientConfig;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.core.CreditEventRepository;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSafetyTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;
    private Object previousConfig;
    private boolean previousConfigRecovery;

    @AfterEach
    void restoreStatics() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        configField().set(null, previousConfig);
        configRecoveryField().setBoolean(null, previousConfigRecovery);
        DataHealth.clearReasons();
    }

    @Test
    void dataHealthDeduplicatesRepeatedReasons() {
        DataHealth.reportRecoveryRequired("identischer Testgrund");
        DataHealth.reportRecoveryRequired("identischer Testgrund");
        DataHealth.reportRecoveryRequired("identischer Testgrund");
        assertEquals(List.of("identischer Testgrund"), DataHealth.reasons());
    }

    @Test
    void transactionRecoveryCanRecheckAnUnhealthyDatabaseEvenBeforeItsOwnFlagIsUpdated() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize();
        Field healthy = databaseField("healthy");
        Field writeLocked = databaseField("writeLocked");
        Field transactionRecovery = TransactionRepository.class.getDeclaredField("recoveryRequired");
        healthy.setAccessible(true);
        writeLocked.setAccessible(true);
        transactionRecovery.setAccessible(true);
        healthy.setBoolean(databaseTarget(), false);
        writeLocked.setBoolean(databaseTarget(), true);
        transactionRecovery.setBoolean(TransactionRepository.getInstance(), false);

        assertTrue(TransactionRepository.getInstance().resetCorruptTransactionsWithBackup());
        assertTrue(database.isHealthy());
        assertFalse(database.isWriteLocked());
    }

    @Test
    void atomicMutationsNeverDeleteUnrelatedDeals() throws Exception {
        CreditManager manager = manager();
        CreditEntry first = manager.createCredit("creditor", "debtor", 100D, null, "first", null);
        CreditEntry second = manager.createCredit("creditor", "other", 80D, null, "second", null);

        var payment = manager.addMoneyPayment(first.getId(), "debtor", 25D);
        CreditRepository afterPayment = new CreditRepository();
        afterPayment.load();
        assertEquals(2, afterPayment.getAllCredits().size());
        assertEquals(25D, afterPayment.findCreditById(first.getId()).orElseThrow().getPaidAmount());
        assertTrue(afterPayment.findCreditById(second.getId()).isPresent());

        manager.deletePayment(payment.getId());
        CreditRepository afterDelete = new CreditRepository();
        afterDelete.load();
        assertEquals(2, afterDelete.getAllCredits().size());
        assertEquals(0D, afterDelete.findCreditById(first.getId()).orElseThrow().getPaidAmount());
        assertTrue(afterDelete.findCreditById(second.getId()).isPresent());
    }

    @Test
    void fullStateReplacementRefusesToTurnExistingDataIntoAnEmptyState() throws Exception {
        CreditManager manager = manager();
        manager.createCredit("creditor", "debtor", 100D, null, "keep", null);

        DatabaseManager database = DatabaseManager.getInstance();
        assertFalse(database.replaceCreditState(List.of(), List.of(), List.of()));
        assertEquals(1, database.loadCreditState().credits().size());
    }

    @Test
    void fullStateReplacementRemovesStaleCreditsPaymentsAndEvents() throws Exception {
        CreditManager manager = manager();
        CreditEntry kept = manager.createCredit("creditor", "debtor", 100D, null, "kept", null);
        CreditEntry stale = manager.createCredit("creditor", "other", 80D, null, "stale", null);
        var keptPayment = manager.addMoneyPayment(kept.getId(), "debtor", 25D);
        manager.addMoneyPayment(stale.getId(), "other", 20D);
        DatabaseManager database = DatabaseManager.getInstance();
        DatabaseManager.DatabaseState state = database.loadCreditState();

        assertTrue(database.replaceCreditState(
                state.credits().stream().filter(credit -> credit.getId().equals(kept.getId())).toList(),
                state.payments().stream().filter(payment -> payment.getId().equals(keptPayment.getId())).toList(),
                state.events().stream().filter(event -> event.getCreditId().equals(kept.getId())).toList()));

        DatabaseManager.DatabaseState replaced = database.loadCreditState();
        assertEquals(List.of(kept.getId()), replaced.credits().stream().map(CreditEntry::getId).toList());
        assertEquals(List.of(keptPayment.getId()), replaced.payments().stream().map(payment -> payment.getId()).toList());
        assertTrue(replaced.events().stream().allMatch(event -> event.getCreditId().equals(kept.getId())));
    }

    @Test
    void fullStateReplacementKeepsPaylogsMigrationRecordsLegacyRecordsAndMetadata() throws Exception {
        CreditManager manager = manager();
        CreditEntry kept = manager.createCredit("creditor", "debtor", 100D, null, "kept", null);
        manager.createCredit("creditor", "other", 80D, null, "stale", null);
        TransactionEntry paylog = testPaylog("preserved", 25D);
        assertTrue(TransactionRepository.getInstance().add(paylog));
        insertNonDomainRecords();
        DatabaseManager database = DatabaseManager.getInstance();
        DatabaseManager.DatabaseState state = database.loadCreditState();

        assertTrue(database.replaceCreditState(
                state.credits().stream().filter(credit -> credit.getId().equals(kept.getId())).toList(),
                List.of(),
                state.events().stream().filter(event -> event.getCreditId().equals(kept.getId())).toList()));

        assertEquals(1L, countRows("paylogs"));
        assertEquals(1L, countRows("migration_log"));
        assertEquals(1L, countRows("legacy_records"));
        assertEquals(1L, countRows("metadata", "meta_key='review_retained'"));
        assertEquals(1L, countRows("credits"));
    }

    @Test
    void fullStateReplacementRefreshesPaylogLinkAmountsAfterRemovingLinkedPayment() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 100D, null, "linked", null);
        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", 80D);
        paylog.setSource("TEST_FIXTURE");
        paylog.setRawText("fixture:linked:paylog");
        assertTrue(TransactionRepository.getInstance().add(paylog));
        assertTrue(manager.linkPaylogToDeal(paylog.getId(), credit.getId()).linked());
        DatabaseManager database = DatabaseManager.getInstance();
        DatabaseManager.DatabaseState state = database.loadCreditState();
        CreditEntry replacement = state.credits().getFirst();
        replacement.replacePayments(List.of());
        replacement.setCompletedAt(null);

        assertTrue(database.replaceCreditState(List.of(replacement), List.of(),
                state.events().stream().filter(event -> event.getType() == CreditEventType.CREDIT_CREATED).toList()));

        assertEquals(0D, paylogLinkedAmount(paylog.getId()));
        assertEquals(0L, paylogLinkCount(paylog.getId()));
    }

    @Test
    void orphanPaymentCreatesOpenErrorHealthRecord() throws Exception {
        manager();
        insertOrphanPayment();

        var findings = DatabaseManager.getInstance().runHealthCheck();

        assertTrue(findings.stream().anyMatch(record -> "PAYMENT_CREDIT_ORPHAN".equals(record.type())
                && "ERROR".equals(record.severity()) && "OPEN".equals(record.status())));
    }

    @Test
    void orphanEventCreatesOpenErrorHealthRecord() throws Exception {
        manager();
        insertOrphanEvent();

        var findings = DatabaseManager.getInstance().runHealthCheck();

        assertTrue(findings.stream().anyMatch(record -> "EVENT_CREDIT_ORPHAN".equals(record.type())
                && "ERROR".equals(record.severity()) && "OPEN".equals(record.status())));
    }

    @Test
    void batchedCreditMutationsCommitOnceWithoutReloadingOrReplacingExistingData() throws Exception {
        manager();
        DatabaseManager database = DatabaseManager.getInstance();
        long revisionBefore = database.revision();
        List<DatabaseManager.CreditMutation> mutations = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> batchMutation(index)).toList();

        assertTrue(database.commitCreditMutationsBatch(mutations));
        assertEquals(revisionBefore + 1, database.revision());
        assertEquals(3, database.loadCreditState().credits().size());
    }

    private DatabaseManager.CreditMutation batchMutation(int index) {
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "batch-deal-" + index, "creditor", "debtor_" + index, 10D + index, null, "TEST_FIXTURE");
        CreditEventEntry event = new CreditEventEntry(CreditEventType.CREDIT_CREATED, credit, credit.getAmount(), credit.getAmount(),
                "TEST_FIXTURE", "creditor", "TEST_FIXTURE", false);
        return new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of(event));
    }

    @Test
    void validatedH2BackupRestoresByQuarantiningTheActiveDatabase() throws Exception {
        CreditManager manager = manager();
        CreditEntry kept = manager.createCredit("creditor", "debtor", 100D, null, "kept", null);
        DatabaseManager database = DatabaseManager.getInstance();
        assertTrue(database.createBackup());
        assertEquals(1, database.listBackups().size());
        assertEquals(1, database.listBackups().getFirst().creditCount());

        manager.createCredit("creditor", "other", 50D, null, "later", null);
        assertTrue(database.restoreLatestValidBackup());
        assertEquals(1, database.loadCreditState().credits().size());
        assertEquals(kept.getId(), database.loadCreditState().credits().getFirst().getId());
        try (var files = Files.list(FileManager.getQuarantineDirectory())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
        }
    }

    @Test
    void emptyActiveDatabaseWithValidatedBackupIsLockedForRecovery() throws Exception {
        CreditManager manager = manager();
        manager.createCredit("creditor", "debtor", 100D, null, "recover", null);
        DatabaseManager database = DatabaseManager.getInstance();
        assertTrue(database.createBackup());

        Path active = FileManager.getDatabaseStorageFile();
        Files.move(active, dataDirectory.resolve("simulated-missing-active.mv.db"));
        resetDatabaseInitialization();
        database.initialize();

        assertTrue(database.isWriteLocked());
        assertFalse(database.isSafeForWrites());
        assertFalse(DataHealth.reasons().isEmpty());
        assertTrue(database.restoreLatestValidBackup());
        assertTrue(database.isSafeForWrites());
        assertEquals(1, database.loadCreditState().credits().size());
    }

    @Test
    void legacyH2BackupIsDetectedAndCanBeRestoredWithoutDeletingIt() throws Exception {
        CreditManager manager = manager();
        manager.createCredit("creditor", "debtor", 100D, null, "legacy", null);
        Files.createDirectories(FileManager.getBackupDirectory());
        Path legacyBackup = FileManager.getBackupDirectory().resolve("creditmanager_backup_legacy.mv.db");
        Files.copy(FileManager.getDatabaseStorageFile(), legacyBackup);
        Files.move(FileManager.getDatabaseStorageFile(), dataDirectory.resolve("simulated-missing-active.mv.db"));
        resetDatabaseInitialization();

        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize();
        assertTrue(database.isWriteLocked());
        assertTrue(database.listBackups().stream().anyMatch(backup -> "legacy-h2".equals(backup.format())));
        assertTrue(database.restoreLatestValidBackup());
        assertTrue(Files.exists(legacyBackup));
        assertEquals(1, database.loadCreditState().credits().size());
    }

    @Test
    void physicallyCorruptDatabaseWithoutBackupIsQuarantinedWithoutCreatingAnEmptyDatabase() throws Exception {
        CreditManager manager = manager();
        manager.createCredit("creditor", "debtor", 100D, null, "keep-quarantined", null);
        DatabaseManager database = DatabaseManager.getInstance();
        Path active = FileManager.getDatabaseStorageFile();
        corrupt(active);
        resetDatabaseInitialization();

        database.initialize();

        assertEquals(DatabaseManager.DatabaseAvailability.NEEDS_USER_RECOVERY, database.availability());
        assertTrue(database.requiresUserRecovery());
        assertFalse(database.isHealthy());
        assertTrue(database.isWriteLocked());
        assertFalse(Files.exists(active));
        assertThrows(IllegalStateException.class, database::loadCreditState);
        try (var files = Files.list(FileManager.getQuarantineDirectory())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
        }
        try (var files = Files.list(FileManager.getQuarantineDirectory())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".manifest.json")));
        }

        database.initialize();
        assertFalse(Files.exists(active));
    }

    @Test
    void physicallyCorruptDatabaseRestoresTheLatestValidatedBackupWithoutReadingItAgain() throws Exception {
        CreditManager manager = manager();
        CreditEntry kept = manager.createCredit("creditor", "debtor", 100D, null, "restore-after-corruption", null);
        DatabaseManager database = DatabaseManager.getInstance();
        assertTrue(database.createBackup());
        Path active = FileManager.getDatabaseStorageFile();
        corrupt(active);
        resetDatabaseInitialization();

        database.initialize();

        assertTrue(database.isHealthy());
        assertFalse(database.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.RESTORED_FROM_BACKUP, database.availability());
        assertEquals(List.of(kept.getId()), database.loadCreditState().credits().stream().map(CreditEntry::getId).toList());
        try (var files = Files.list(FileManager.getQuarantineDirectory())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
        }
    }

    @Test
    void detailedPaylogBatchReportsDuplicatesWithoutRollingBackValidEntries() throws Exception {
        useTemporaryDataDirectory();
        TransactionEntry original = testPaylog("batch", 12D);
        TransactionEntry duplicate = testPaylog("batch", 12D);
        duplicate.setTimestamp(original.getTimestamp());
        duplicate.setRawText(original.getRawText());
        DatabaseManager.BatchInsertResult result = DatabaseManager.getInstance().addPaylogsBatchDetailed(List.of(original, duplicate));

        assertEquals(2, result.requested());
        assertEquals(1, result.inserted());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failed());
    }

    private CreditManager manager() throws Exception {
        useTemporaryDataDirectory();
        CreditRepository repository = new CreditRepository();
        repository.load();
        CreditEventRepository.getInstance().bind(repository);
        CreditEventRepository.getInstance().load();
        TransactionRepository.getInstance().load();
        return new CreditManager(repository);
    }

    private void useTemporaryDataDirectory() throws Exception {
        Files.createDirectories(dataDirectory);
        previousDirectory = (Path) dataDirectoryField().get(null);
        previousConfig = configField().get(null);
        previousConfigRecovery = configRecoveryField().getBoolean(null);
        dataDirectoryField().set(null, dataDirectory);
        configField().set(null, new ClientConfig());
        configRecoveryField().setBoolean(null, false);
        DataHealth.clearReasons();
    }

    private void resetDatabaseInitialization() throws Exception {
        Field initialized = databaseField("initialized");
        Field initializedAt = databaseField("initializedAt");
        Field healthy = databaseField("healthy");
        Field writeLocked = databaseField("writeLocked");
        Field availability = databaseField("availability");
        initialized.setAccessible(true);
        initializedAt.setAccessible(true);
        healthy.setAccessible(true);
        writeLocked.setAccessible(true);
        availability.setAccessible(true);
        Object target = databaseTarget();
        initialized.setBoolean(target, false);
        initializedAt.set(target, null);
        healthy.setBoolean(target, true);
        writeLocked.setBoolean(target, false);
        availability.set(target, DatabaseManager.DatabaseAvailability.UNKNOWN);
    }

    private void corrupt(Path active) throws Exception {
        byte[] contents = Files.readAllBytes(active);
        Files.write(active, Arrays.copyOf(contents, Math.min(contents.length, 16)));
    }

    private TransactionEntry testPaylog(String runId, double amount) {
        TransactionEntry entry = new TransactionEntry("debtor_" + runId, "creditor", amount);
        entry.setSource("TEST_FIXTURE");
        entry.setRawText("fixture:" + runId + ": paylog");
        entry.setMetadata("{\"testRunId\":\"" + runId + "\"}");
        return entry;
    }

    private void insertNonDomainRecords() throws Exception {
        try (var connection = openConnection()) {
            try (var migration = connection.prepareStatement("INSERT INTO migration_log (id, migration_type, started_at, completed_at, details, status) VALUES (?, ?, ?, ?, ?, ?)");
                 var legacy = connection.prepareStatement("INSERT INTO legacy_records (id, record_kind, original_id, raw_payload, reason, created_at, migration_id) VALUES (?, ?, ?, ?, ?, ?, ?)");
                 var metadata = connection.prepareStatement("MERGE INTO metadata (meta_key, meta_value) KEY(meta_key) VALUES (?, ?)")) {
                long now = System.currentTimeMillis();
                String migrationId = UUID.randomUUID().toString();
                migration.setString(1, migrationId); migration.setString(2, "REVIEW_FIXTURE"); migration.setLong(3, now); migration.setLong(4, now); migration.setString(5, "fixture"); migration.setString(6, "COMPLETED"); migration.executeUpdate();
                legacy.setString(1, UUID.randomUUID().toString()); legacy.setString(2, "REVIEW_FIXTURE"); legacy.setString(3, "fixture"); legacy.setString(4, "{}"); legacy.setString(5, "fixture"); legacy.setLong(6, now); legacy.setString(7, migrationId); legacy.executeUpdate();
                metadata.setString(1, "review_retained"); metadata.setString(2, "true"); metadata.executeUpdate();
            }
        }
    }

    private void insertOrphanPayment() throws Exception {
        try (var connection = openConnection(); var statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            statement.executeUpdate("INSERT INTO payments (id, credit_id, from_player, to_player, amount, items_json, created_at, source, revision) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 'debtor', 'creditor', 5, '[]', 1, 'MANUAL', 0)");
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    private void insertOrphanEvent() throws Exception {
        try (var connection = openConnection(); var statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            statement.executeUpdate("INSERT INTO credit_events (id, credit_id, event_type, amount, paid_after, remaining_after, created_at, deal_name, creditor, debtor, note, amount_before, amount_after, actor, source, item_payment, revision) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 'CREDIT_CREATED', 5, 0, 5, 1, 'orphan', 'creditor', 'debtor', NULL, 0, 0, 'creditor', 'MANUAL', FALSE, 0)");
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    private long countRows(String table) throws Exception {
        return countRows(table, "1=1");
    }

    private long countRows(String table, String condition) throws Exception {
        try (var connection = openConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + condition)) {
            result.next();
            return result.getLong(1);
        }
    }

    private double paylogLinkedAmount(UUID paylogId) throws Exception {
        try (var connection = openConnection(); var statement = connection.prepareStatement("SELECT linked_amount FROM paylogs WHERE id=?")) {
            statement.setString(1, paylogId.toString());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getDouble(1);
            }
        }
    }

    private long paylogLinkCount(UUID paylogId) throws Exception {
        try (var connection = openConnection(); var statement = connection.prepareStatement("SELECT link_count FROM paylogs WHERE id=?")) {
            statement.setString(1, paylogId.toString());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private java.sql.Connection openConnection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE");
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }

    private Field databaseField(String name) throws Exception {
        Field field = DatabaseCoordinator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Object databaseTarget() throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("coordinator");
        field.setAccessible(true);
        return field.get(DatabaseManager.getInstance());
    }

    private Field configField() throws Exception {
        Field field = ClientConfigManager.class.getDeclaredField("config");
        field.setAccessible(true);
        return field;
    }

    private Field configRecoveryField() throws Exception {
        Field field = ClientConfigManager.class.getDeclaredField("recoveryRequired");
        field.setAccessible(true);
        return field;
    }
}
