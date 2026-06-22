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
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Field initialized = DatabaseManager.class.getDeclaredField("initialized");
        Field initializedAt = DatabaseManager.class.getDeclaredField("initializedAt");
        Field healthy = DatabaseManager.class.getDeclaredField("healthy");
        Field writeLocked = DatabaseManager.class.getDeclaredField("writeLocked");
        initialized.setAccessible(true);
        initializedAt.setAccessible(true);
        healthy.setAccessible(true);
        writeLocked.setAccessible(true);
        initialized.setBoolean(DatabaseManager.getInstance(), false);
        initializedAt.set(DatabaseManager.getInstance(), null);
        healthy.setBoolean(DatabaseManager.getInstance(), true);
        writeLocked.setBoolean(DatabaseManager.getInstance(), false);
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
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
