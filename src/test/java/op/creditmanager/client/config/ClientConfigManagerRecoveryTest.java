package op.creditmanager.client.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigManagerRecoveryTest {
    @TempDir
    Path dataDirectory;

    private Path previousDirectory;
    private Object previousConfig;
    private boolean previousRecoveryRequired;

    @AfterEach
    void restoreStatics() throws Exception {
        fileManagerDirectory().set(null, previousDirectory);
        configField().set(null, previousConfig);
        recoveryField().setBoolean(null, previousRecoveryRequired);
        DataHealth.clearReasons();
    }

    @Test
    void corruptConfigCanOnlyBeReplacedByAnExplicitBackedUpDefaultReset() throws Exception {
        prepareTemporaryConfig();
        Files.writeString(FileManager.getClientConfigFile(), "{defekt");

        assertFalse(ClientConfigManager.isWritable());
        assertTrue(ClientConfigManager.resetCorruptConfigWithDefaults());
        assertTrue(ClientConfigManager.isWritable());
        assertTrue(Files.isDirectory(dataDirectory.resolve("backups")));
    }

    private void prepareTemporaryConfig() throws Exception {
        Files.createDirectories(dataDirectory);
        previousDirectory = (Path) fileManagerDirectory().get(null);
        previousConfig = configField().get(null);
        previousRecoveryRequired = recoveryField().getBoolean(null);
        fileManagerDirectory().set(null, dataDirectory);
        configField().set(null, null);
        recoveryField().setBoolean(null, false);
        DataHealth.clearReasons();
    }

    private Field fileManagerDirectory() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }

    private Field configField() throws Exception {
        Field field = ClientConfigManager.class.getDeclaredField("config");
        field.setAccessible(true);
        return field;
    }

    private Field recoveryField() throws Exception {
        Field field = ClientConfigManager.class.getDeclaredField("recoveryRequired");
        field.setAccessible(true);
        return field;
    }
}
