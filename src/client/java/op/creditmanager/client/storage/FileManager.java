package op.creditmanager.client.storage;

import op.creditmanager.client.CreditManagerClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class FileManager {

    private static Path dataDirectory;

    public static void initialize() {
        if (dataDirectory != null) return;

        dataDirectory = FabricLoader.getInstance()
                .getGameDir()
                .resolve("CreditManagerLogs");

        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
                CreditManagerClient.LOGGER.info("Created CreditManagerLogs directory at: " + dataDirectory);
            }
        } catch (IOException e) {
            CreditManagerClient.LOGGER.error("Failed to create CreditManagerLogs directory", e);
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

    public static void tidyAfterSuccessfulSave() {
        try {
            Path backups = dataDirectory.resolve("backups");
            Files.createDirectories(backups);
            try (var files = Files.list(dataDirectory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".tmp")).forEach(FileManager::deleteQuietly);
            }
            try (var files = Files.list(backups)) {
                files.filter(Files::isRegularFile).sorted(Comparator.comparingLong(FileManager::modified).reversed()).skip(12).forEach(FileManager::deleteQuietly);
            }
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not tidy CreditManager data directory", exception);
        }
    }

    private static long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0L; } }
    private static void deleteQuietly(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
}
