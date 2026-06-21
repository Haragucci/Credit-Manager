package op.creditmanager.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import op.creditmanager.client.CreditManagerClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsonStorage {

    public record LoadResult<T>(T value, boolean missing, boolean recoveryRequired) { }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private static final ReadWriteLock LOCK = new ReentrantReadWriteLock();

    public static <T> LoadResult<T> load(Path path, Type type, T defaultValue) {
        LOCK.readLock().lock();
        try {
            if (!Files.exists(path)) {
                return new LoadResult<>(defaultValue, true, false);
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            T result = GSON.fromJson(json, type);
            return new LoadResult<>(result != null ? result : defaultValue, false, false);
        } catch (IOException e) {
            CreditManagerClient.LOGGER.error("Failed to load file: " + path, e);
            DataHealth.reportRecoveryRequired(path, createBackup(path));
            return new LoadResult<>(defaultValue, false, true);
        } catch (Exception e) {
            CreditManagerClient.LOGGER.error("Corrupted JSON file: " + path + " - creating backup and resetting", e);
            DataHealth.reportRecoveryRequired(path, createBackup(path));
            return new LoadResult<>(defaultValue, false, true);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static boolean save(Path path, Object data) {
        LOCK.writeLock().lock();
        try {
            String json = GSON.toJson(data);
            Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tempPath, json, StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            CreditManagerClient.LOGGER.error("Failed to save file: " + path, e);
            return false;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static boolean createBackup(Path path) {
        try {
            if (Files.exists(path)) {
                Path backup = FileManager.getBackupFile(path.getFileName().toString());
                Path parent = backup.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
                CreditManagerClient.LOGGER.info("Created backup: " + backup);
                return true;
            }
        } catch (IOException e) {
            CreditManagerClient.LOGGER.error("Failed to create backup for: " + path, e);
        }
        return false;
    }
}
