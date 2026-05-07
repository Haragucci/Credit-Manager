package op.creditmanager.client.storage;

import op.creditmanager.client.CreditManagerClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public static Path getPlayersFile() {
        return dataDirectory.resolve("players.json");
    }

    public static Path getPaymentsFile() {
        return dataDirectory.resolve("payments.json");
    }

    public static Path getTransactionsFile() {
        return dataDirectory.resolve("transactions.json");
    }

    public static Path getBackupFile(String originalName) {
        String backupName = originalName.replace(".json",
                "_backup_" + System.currentTimeMillis() + ".json");
        return dataDirectory.resolve(backupName);
    }
}