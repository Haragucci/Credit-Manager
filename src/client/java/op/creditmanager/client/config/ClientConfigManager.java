package op.creditmanager.client.config;

import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;

import java.nio.file.Path;

/** Loads and persists the client preference without touching credit files. */
public final class ClientConfigManager {

    private static ClientConfig config;

    private ClientConfigManager() {
    }

    public static synchronized GuiMode getGuiMode() {
        return getConfig().getGuiMode();
    }

    public static synchronized void setGuiMode(GuiMode guiMode) {
        ClientConfig loaded = getConfig();
        loaded.setGuiMode(guiMode);
        save(loaded);
    }

    public static synchronized boolean isAutomaticPaylogDetection() {
        return getConfig().isAutomaticPaylogDetection();
    }

    public static synchronized void setAutomaticPaylogDetection(boolean enabled) {
        ClientConfig loaded = getConfig();
        loaded.setAutomaticPaylogDetection(enabled);
        save(loaded);
    }

    public static synchronized boolean isDetectPaylogsInOverlay() {
        return getConfig().isDetectPaylogsInOverlay();
    }

    public static synchronized void setDetectPaylogsInOverlay(boolean enabled) {
        ClientConfig loaded = getConfig();
        loaded.setDetectPaylogsInOverlay(enabled);
        save(loaded);
    }

    public static synchronized boolean isShowPaylogNotifications() {
        return getConfig().isShowPaylogNotifications();
    }

    public static synchronized void setShowPaylogNotifications(boolean enabled) {
        ClientConfig loaded = getConfig();
        loaded.setShowPaylogNotifications(enabled);
        save(loaded);
    }

    public static synchronized void reload() {
        config = null;
        getConfig();
    }

    private static ClientConfig getConfig() {
        if (config != null) {
            return config;
        }

        FileManager.initialize();
        Path path = FileManager.getClientConfigFile();
        config = JsonStorage.load(path, ClientConfig.class, new ClientConfig());
        config.normalize();

        // Also writes a missing config and replaces a corrupt file after
        // JsonStorage has created its timestamped backup.
        save(config);
        return config;
    }

    private static void save(ClientConfig value) {
        value.normalize();
        JsonStorage.save(FileManager.getClientConfigFile(), value);
    }
}
