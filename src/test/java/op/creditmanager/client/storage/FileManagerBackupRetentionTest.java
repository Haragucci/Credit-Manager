package op.creditmanager.client.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileManagerBackupRetentionTest {
    @TempDir
    Path dataDirectory;
    private Path previousDirectory;

    @AfterEach
    void restoreDataDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
    }

    @Test
    void retainsBackupsIndependentlyPerBackupType() throws Exception {
        Files.createDirectories(dataDirectory);
        previousDirectory = (Path) dataDirectoryField().get(null);
        dataDirectoryField().set(null, dataDirectory);
        Path backups = FileManager.getBackupDirectory();
        Files.createDirectories(backups);
        createBackups(backups, "creditmanager", ".zip");
        createBackups(backups, "client_config", ".json");

        FileManager.tidyAfterSuccessfulSave();

        try (var files = Files.list(backups)) {
            assertEquals(12L, files.filter(path -> path.getFileName().toString().startsWith("creditmanager_backup_")).count());
        }
        try (var files = Files.list(backups)) {
            assertEquals(12L, files.filter(path -> path.getFileName().toString().startsWith("client_config_backup_")).count());
        }
    }

    private void createBackups(Path backups, String type, String extension) throws Exception {
        for (int index = 0; index < 13; index++) {
            Path backup = backups.resolve(type + "_backup_" + index + extension);
            Files.writeString(backup, "backup");
            Files.setLastModifiedTime(backup, FileTime.fromMillis(index));
        }
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
