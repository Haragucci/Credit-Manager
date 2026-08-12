package op.creditmanager.client.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStorageTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;

    @AfterEach
    void restoreDataDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        DataHealth.clearReasons();
    }

    @Test
    void literalNullRootRequiresRecoveryAndPreservesBackup() throws Exception {
        Field field = dataDirectoryField();
        previousDirectory = (Path) field.get(null);
        field.set(null, dataDirectory);
        FileManager.initialize();
        Path config = dataDirectory.resolve("client_config.json");
        Files.writeString(config, "null");

        JsonStorage.LoadResult<String> result = JsonStorage.load(config, String.class, "safe-default");

        assertEquals("safe-default", result.value());
        assertFalse(result.missing());
        assertTrue(result.recoveryRequired());
        try (var backups = Files.list(FileManager.getBackupDirectory())) {
            assertTrue(backups.anyMatch(path -> path.getFileName().toString().startsWith("client_config_backup_")));
        }
        assertFalse(DataHealth.reasons().isEmpty());
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
