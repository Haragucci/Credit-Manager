package op.creditmanager.client.storage;

import op.creditmanager.client.CreditManagerClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public class FileManager {

    private static Path dataDirectory;
    private static Path initializedDirectory;
    private static Path backupMirrorDirectory;
    private static StorageRootResolver.StorageLocation storageLocation;
    private static ProcessStorageLease storageLease;
    private static StorageAccessState storageAccessState = StorageAccessState.UNINITIALIZED;
    private static boolean dataDirectoryCreated;

    public static synchronized void initialize() {
        if (dataDirectory != null) {
            Path normalized = dataDirectory.toAbsolutePath().normalize();
            if (!normalized.equals(initializedDirectory)) {
                releaseLeaseQuietly();
                initializedDirectory = normalized;
                storageLocation = new StorageRootResolver.StorageLocation(normalized, null,
                        StorageRootResolver.StorageEnvironment.STANDARD, "externally-managed",
                        StorageRootResolver.ResolutionSource.STANDARD_GAME_DIR, true, java.util.List.of("externally-managed"));
                storageAccessState = StorageAccessState.EXTERNALLY_MANAGED;
                dataDirectoryCreated = false;
            }
            return;
        }
        Path gameDirectory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        storageLocation = new StorageRootResolver().resolve(gameDirectory);
        dataDirectory = storageLocation.canonicalRoot();
        initializedDirectory = dataDirectory;
        if (!storageLocation.resolved()) {
            storageAccessState = StorageAccessState.STORAGE_LOCATION_UNRESOLVED;
            CreditManagerClient.LOGGER.error("CreditManager persistent storage could not be resolved safely for {}", storageLocation.environment());
            return;
        }
        BackupMirrorPathResolver.Resolution mirror = new BackupMirrorPathResolver().resolve(dataDirectory, storageLocation);
        backupMirrorDirectory = mirror.enabled() ? mirror.root() : null;
        if (!mirror.enabled()) CreditManagerClient.LOGGER.warn("CreditManager backup mirror is disabled: {}", mirror.reason());
        try {
            dataDirectoryCreated = !Files.exists(dataDirectory);
            Files.createDirectories(dataDirectory);
            Optional<ProcessStorageLease> acquired = ProcessStorageLease.tryAcquire(dataDirectory);
            if (acquired.isEmpty()) {
                storageAccessState = StorageAccessState.SECONDARY_INSTANCE;
                CreditManagerClient.LOGGER.warn("CreditManager storage is already leased by another process: {}", dataDirectory);
                return;
            }
            storageLease = acquired.get();
            storageAccessState = StorageAccessState.PRIMARY;
            CreditManagerClient.LOGGER.info("CreditManager storage environment: {}, root: {}", storageLocation.environment(), dataDirectory);
        } catch (IOException e) {
            storageAccessState = StorageAccessState.STORAGE_IO_FAILED;
            CreditManagerClient.LOGGER.error("Failed to initialise persistent CreditManager storage", e);
        }
    }

    public static synchronized boolean retryStorageLease() {
        if (storageAccessState != StorageAccessState.SECONDARY_INSTANCE && storageAccessState != StorageAccessState.STORAGE_IO_FAILED) return false;
        if (storageLocation == null || !storageLocation.resolved()) return false;
        try {
            Optional<ProcessStorageLease> acquired = ProcessStorageLease.tryAcquire(dataDirectory);
            if (acquired.isEmpty()) return false;
            storageLease = acquired.get();
            storageAccessState = StorageAccessState.PRIMARY;
            return true;
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not retry CreditManager storage lease", exception);
            return false;
        }
    }

    public static synchronized void shutdown() {
        releaseLeaseQuietly();
        if (storageAccessState == StorageAccessState.PRIMARY) storageAccessState = StorageAccessState.RELEASED;
    }

    public static synchronized boolean databaseAccessAllowed() {
        return storageAccessState == StorageAccessState.PRIMARY || storageAccessState == StorageAccessState.EXTERNALLY_MANAGED;
    }

    public static synchronized boolean hasPrimaryStorageLease() {
        return storageAccessState == StorageAccessState.EXTERNALLY_MANAGED
                || storageAccessState == StorageAccessState.PRIMARY && storageLease != null && storageLease.isHeld();
    }

    public static synchronized StorageAccessState storageAccessState() {
        return storageAccessState;
    }

    public static synchronized StorageRootResolver.StorageLocation storageLocation() {
        return storageLocation;
    }

    public static synchronized boolean wasDataDirectoryCreated() {
        return dataDirectoryCreated;
    }

    private static void releaseLeaseQuietly() {
        if (storageLease == null) return;
        try {
            storageLease.close();
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not release CreditManager storage lease cleanly", exception);
        } finally {
            storageLease = null;
        }
    }

    public static Path getDataDirectory() {
        return dataDirectory;
    }

    public static Path getCreditsFile() {
        return dataDirectory.resolve("credits.json");
    }

    public static Path getCreditStateFile() {
        return dataDirectory.resolve("credit_state.json");
    }

    public static Path getPlayersFile() {
        return dataDirectory.resolve("players.json");
    }

    public static Path getPaymentsFile() {
        return dataDirectory.resolve("payments.json");
    }

    public static Path getTransactionsFile() {
        return dataDirectory.resolve("transactions.json");
    }

    public static Path getCreditEventsFile() {
        return dataDirectory.resolve("credit_events.json");
    }

    public static Path getClientConfigFile() {
        return dataDirectory.resolve("client_config.json");
    }

    public static Path getDatabaseFile() {
        return dataDirectory.resolve("creditmanager");
    }

    public static Path getDatabaseStorageFile() {
        return dataDirectory.resolve("creditmanager.mv.db");
    }

    public static Path getStorageIdentityFile() {
        return dataDirectory.resolve("storage_identity.json");
    }

    public static Path getLegacyDataDirectory() {
        return storageLocation == null ? null : storageLocation.legacyRoot();
    }

    public static Path getLegacyArchiveDirectory() {
        return dataDirectory.resolve("legacy-json");
    }

    public static Path getBackupFile(String originalName) {
        boolean h2Database = originalName.endsWith(".mv.db");
        int extension = h2Database ? originalName.length() - ".mv.db".length() : originalName.lastIndexOf('.');
        String base = extension > 0 ? originalName.substring(0, extension) : originalName;
        String suffix = h2Database ? ".mv.db" : extension > 0 ? originalName.substring(extension) : ".bak";
        String backupName = base + "_backup_" + System.currentTimeMillis() + suffix;
        return dataDirectory.resolve("backups").resolve(backupName);
    }

    public static Path getBackupDirectory() {
        return dataDirectory.resolve("backups");
    }

    public static Path getBackupMirrorDirectory() {
        return backupMirrorDirectory;
    }

    public static Path getBackupManifestFile() {
        return getBackupDirectory().resolve("manifest.json");
    }

    public static Path getRecoveryDirectory() {
        return dataDirectory.resolve("recovery");
    }

    public static Path getQuarantineDirectory() {
        return getRecoveryDirectory().resolve("quarantine");
    }

    public static Path getRecoveryValidationDirectory() {
        return getRecoveryDirectory().resolve("validation");
    }

    public static Path getDiscardRecoveryDirectory() {
        return getRecoveryDirectory().resolve("discard-snapshots");
    }

    public static void tidyAfterSuccessfulSave() {
        try {
            Path backups = dataDirectory.resolve("backups");
            Files.createDirectories(backups);
            try (var files = Files.list(dataDirectory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".tmp")).forEach(FileManager::deleteQuietly);
            }
            try (var files = Files.list(backups)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                            return !name.endsWith(".zip") && !name.endsWith(".mv.db") && !name.equals("manifest.json");
                        })
                        .collect(java.util.stream.Collectors.groupingBy(FileManager::backupType))
                        .values()
                        .forEach(group -> group.stream().sorted(Comparator.comparingLong(FileManager::modified).reversed()).skip(12).forEach(FileManager::archiveRetiredBackup));
            }
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not tidy CreditManager data directory", exception);
        }
    }

    private static long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0L; } }
    private static String backupType(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int marker = name.indexOf("_backup_");
        return marker > 0 ? name.substring(0, marker) : name;
    }
    private static void deleteQuietly(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
    private static void archiveRetiredBackup(Path path) {
        Path target = getRecoveryDirectory().resolve("retired-file-backups").resolve(path.getFileName() + "." + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(path, target);
            }
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not archive retired CreditManager file backup: {}", path, exception);
        }
    }

    public enum StorageAccessState {
        UNINITIALIZED,
        PRIMARY,
        EXTERNALLY_MANAGED,
        SECONDARY_INSTANCE,
        STORAGE_LOCATION_UNRESOLVED,
        STORAGE_IO_FAILED,
        RELEASED
    }
}
