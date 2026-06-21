package op.creditmanager.client.config;

import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;
import op.creditmanager.client.gui.modern.theme.ModernThemeMode;

import java.nio.file.Path;

public final class ClientConfigManager {

    private static ClientConfig config;
    private static boolean recoveryRequired;

    private ClientConfigManager() {
    }

    public static synchronized boolean isAutomaticPaylogDetection() {
        return getConfig().isAutomaticPaylogDetection();
    }

    public static synchronized boolean setAutomaticPaylogDetection(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setAutomaticPaylogDetection(enabled);
        return save(loaded);
    }

    public static synchronized boolean isDetectPaylogsInOverlay() {
        return getConfig().isDetectPaylogsInOverlay();
    }

    public static synchronized boolean setDetectPaylogsInOverlay(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setDetectPaylogsInOverlay(enabled);
        return save(loaded);
    }

    public static synchronized boolean isShowPaylogNotifications() {
        return getConfig().isShowPaylogNotifications();
    }

    public static synchronized boolean setShowPaylogNotifications(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setShowPaylogNotifications(enabled);
        return save(loaded);
    }

    public static synchronized ModernThemeMode getModernThemeMode() {
        return getConfig().getModernThemeMode();
    }

    public static synchronized boolean setModernThemeMode(ModernThemeMode mode) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setModernThemeMode(mode);
        return save(loaded);
    }

    public static synchronized GuiFontMode getGuiFontMode() {
        return getConfig().getGuiFontMode();
    }

    public static synchronized boolean setGuiFontMode(GuiFontMode mode) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setGuiFontMode(mode);
        return save(loaded);
    }

    public static synchronized int getCustomMainColor() {
        return getConfig().getCustomMainColor();
    }

    public static synchronized boolean setCustomMainColor(int color) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCustomMainColor(color);
        return save(loaded);
    }

    public static synchronized int getCustomAccentColor() {
        return getConfig().getCustomAccentColor();
    }

    public static synchronized boolean setCustomAccentColor(int color) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCustomAccentColor(color);
        return save(loaded);
    }

    public static synchronized int getStatisticsDefaultPeriodDays() {
        return getConfig().getStatisticsDefaultPeriodDays();
    }

    public static synchronized boolean setStatisticsDefaultPeriodDays(int days) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setStatisticsDefaultPeriodDays(days);
        return save(loaded);
    }

    public static synchronized boolean setCustomTheme(int mainColor, int accentColor, ModernThemeMode mode) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCustomMainColor(mainColor);
        loaded.setCustomAccentColor(accentColor);
        loaded.setModernThemeMode(mode);
        return save(loaded);
    }

    public static synchronized boolean isCheckedForJsonMigration() { return getConfig().isCheckedForJsonMigration(); }
    public static synchronized boolean markJsonMigrationChecked(boolean completed) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCheckedForJsonMigration(true);
        loaded.setJsonMigrationCompleted(completed);
        loaded.setJsonMigrationVersion(1);
        return save(loaded);
    }
    public static synchronized boolean resetJsonMigrationCheck() {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCheckedForJsonMigration(false);
        loaded.setJsonMigrationCompleted(false);
        loaded.setJsonMigrationVersion(0);
        return save(loaded);
    }

    public static synchronized boolean isWritable() {
        getConfig();
        return !recoveryRequired;
    }

    public static synchronized void reload() {
        config = null;
        recoveryRequired = false;
        getConfig();
    }

    /** Explicit user action for a corrupt configuration; the original file is kept as a backup. */
    public static synchronized boolean resetCorruptConfigWithDefaults() {
        getConfig();
        if (!recoveryRequired) return false;
        Path path = FileManager.getClientConfigFile();
        if (!JsonStorage.createBackup(path)) return false;
        ClientConfig defaults = new ClientConfig();
        defaults.normalize();
        recoveryRequired = false;
        if (JsonStorage.save(path, defaults)) {
            config = defaults;
            return true;
        }
        recoveryRequired = true;
        return false;
    }

    private static ClientConfig getConfig() {
        if (config != null) {
            return config;
        }

        FileManager.initialize();
        Path path = FileManager.getClientConfigFile();
        JsonStorage.LoadResult<ClientConfig> result = JsonStorage.load(path, ClientConfig.class, new ClientConfig());
        config = result.value();
        recoveryRequired = result.recoveryRequired();
        config.normalize();

        if (result.missing()) save(config);
        return config;
    }

    private static boolean save(ClientConfig value) {
        if (recoveryRequired) return false;
        value.normalize();
        if (JsonStorage.save(FileManager.getClientConfigFile(), value)) return true;
        config = null;
        getConfig();
        return false;
    }

    private static ClientConfig writableConfig() {
        ClientConfig loaded = getConfig();
        return recoveryRequired ? null : loaded;
    }
}
