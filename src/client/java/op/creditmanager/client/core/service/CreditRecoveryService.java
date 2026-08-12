package op.creditmanager.client.core.service;

import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class CreditRecoveryService {
    private static final UUID CONFIG_RECOVERY_TOKEN = UUID.nameUUIDFromBytes("creditmanager-config-recovery".getBytes(StandardCharsets.UTF_8));
    private static final UUID TRANSACTION_RECOVERY_TOKEN = UUID.nameUUIDFromBytes("creditmanager-transaction-recovery".getBytes(StandardCharsets.UTF_8));
    private final CreditRepository repository;
    private final BooleanSupplier componentsWritable;
    private final Runnable reload;

    public CreditRecoveryService(CreditRepository repository, BooleanSupplier componentsWritable, Runnable reload) {
        this.repository = repository;
        this.componentsWritable = componentsWritable;
        this.reload = reload;
    }

    public boolean isWritable() { return componentsWritable.getAsBoolean(); }
    public boolean requiresRecovery() { return !isWritable() || !repository.getRecoveryRecords().isEmpty(); }

    public List<CreditRepository.RecoveryRecord> records() {
        List<CreditRepository.RecoveryRecord> records = new ArrayList<>(repository.getRecoveryRecords());
        if (!ClientConfigManager.isWritable()) records.add(new CreditRepository.RecoveryRecord(CONFIG_RECOVERY_TOKEN,
                CreditRepository.RecoveryType.CONFIG, "client_config.json", null, null, null, FileManager.getClientConfigFile(), "Beschädigte lokale Konfiguration"));
        if (!TransactionRepository.getInstance().isWritable()) records.add(new CreditRepository.RecoveryRecord(TRANSACTION_RECOVERY_TOKEN,
                CreditRepository.RecoveryType.TRANSACTION_LOG, "creditmanager.mv.db", null, null, null, FileManager.getDatabaseStorageFile(), "Beschädigte Paylog-Daten"));
        return List.copyOf(records);
    }

    public boolean repairCredit(UUID token, String creditor, String debtor, double amount, long createdAt) {
        return repository.repairCredit(token, creditor, debtor, amount, createdAt);
    }

    public boolean repairPayment(UUID token, UUID creditId, double amount, long timestamp) {
        return repository.repairPayment(token, creditId, amount, timestamp);
    }

    public boolean repairEvent(UUID token, UUID creditId, CreditEventType type, double amount, long timestamp) {
        return repository.repairEvent(token, creditId, type, amount, timestamp);
    }

    public boolean ignore(UUID token) { return repository.ignoreRecovery(token); }

    public boolean discard(UUID token, boolean confirmed) { return repository.discardRecovery(token, confirmed); }

    public boolean createRecoveryBackup(UUID token) {
        if (CONFIG_RECOVERY_TOKEN.equals(token)) return JsonStorage.createBackup(FileManager.getClientConfigFile());
        if (TRANSACTION_RECOVERY_TOKEN.equals(token)) return DatabaseManager.getInstance().createBackup();
        return repository.createRecoveryBackup(token);
    }

    public boolean createSafetyBackup() { return DatabaseManager.getInstance().createBackup(); }

    public boolean restoreLatestSafetyBackup() {
        if (!DatabaseManager.getInstance().restoreLatestValidBackup()) return false;
        reload.run();
        return !requiresRecovery();
    }

    public boolean recheckAndRepairDatabase() {
        if (!DatabaseManager.getInstance().recheckAndRepair()) return false;
        reload.run();
        return !requiresRecovery();
    }

    public boolean createEmptyDatabaseAfterPhysicalRecovery() {
        if (!DatabaseManager.getInstance().createEmptyDatabaseAfterPhysicalRecovery()) return false;
        reload.run();
        return !requiresRecovery();
    }

    public boolean repairDefault(UUID token) {
        if (CONFIG_RECOVERY_TOKEN.equals(token)) return ClientConfigManager.resetCorruptConfigWithDefaults();
        if (TRANSACTION_RECOVERY_TOKEN.equals(token)) return TransactionRepository.getInstance().resetCorruptTransactionsWithBackup();
        return false;
    }

    public int repairWithSafeDefaults() {
        int repaired = repository.repairWithSafeDefaults();
        if (!ClientConfigManager.isWritable() && ClientConfigManager.resetCorruptConfigWithDefaults()) repaired++;
        if (!TransactionRepository.getInstance().isWritable() && TransactionRepository.getInstance().resetCorruptTransactionsWithBackup()) repaired++;
        return repaired;
    }

    public void reloadData() {
        reload.run();
        if (!requiresRecovery()) DataHealth.clearReasons();
    }
}
