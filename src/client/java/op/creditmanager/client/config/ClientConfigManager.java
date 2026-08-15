package op.creditmanager.client.config;

import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;
import op.creditmanager.client.gui.modern.theme.ModernThemeFactory;
import op.creditmanager.client.gui.modern.theme.ModernThemeMode;

import java.nio.file.Path;

public final class ClientConfigManager {

    private static volatile ClientConfig config;
    private static volatile boolean recoveryRequired;
    private static ModernThemeMode renderedThemeMode = ModernThemeMode.DARK;
    private static GuiFontMode renderedFontMode = GuiFontMode.MOD;
    private static int renderedMainColor = 0xFF1F7A3A;
    private static int renderedAccentColor = 0xFF7EE787;
    private static long renderEpoch;
    private static long fontEpoch;
    private static volatile UiRenderConfig renderConfig = new UiRenderConfig(
            ModernThemeFactory.create(renderedThemeMode, renderedMainColor, renderedAccentColor),
            renderedFontMode, renderEpoch, fontEpoch);
    private static volatile RuntimeConfig runtimeConfig = RuntimeConfig.defaults();
    private static volatile ClientConfig runtimeConfigSource;

    private record RuntimeConfig(boolean automaticPaylogDetection, boolean autoLinkDetectedPaylogsToDeals,
                                 boolean detectPaylogsInOverlay, boolean showAnyPaylogFlyIns,
                                 boolean showDealDetectionPaylogFlyIns, PaylogAutoLinkMode paylogAutoLinkMode,
                                 boolean completeDealOnPaylogOverpay, boolean notifyWhenPaylogHasMatchingDeal,
                                 boolean notifyWhenPaylogHasNoMatchingDeal,
                                 boolean notifyWhenPaylogHasMultipleMatchingDeals,
                                 ModernThemeMode modernThemeMode, GuiFontMode guiFontMode,
                                 int customMainColor, int customAccentColor, int statisticsDefaultPeriodDays,
                                 boolean checkedForJsonMigration, boolean bankPaylogImportOnboardingHandled) {
        private static RuntimeConfig defaults() {
            ClientConfig defaults = new ClientConfig();
            defaults.normalize();
            return from(defaults);
        }

        private static RuntimeConfig from(ClientConfig value) {
            return new RuntimeConfig(value.isAutomaticPaylogDetection(),
                    value.isAutoLinkDetectedPaylogsToDeals(), value.isDetectPaylogsInOverlay(),
                    value.isShowAnyPaylogFlyIns(), value.isShowDealDetectionPaylogFlyIns(),
                    value.getPaylogAutoLinkMode(), value.isCompleteDealOnPaylogOverpay(),
                    value.isNotifyWhenPaylogHasMatchingDeal(), value.isNotifyWhenPaylogHasNoMatchingDeal(),
                    value.isNotifyWhenPaylogHasMultipleMatchingDeals(), value.getModernThemeMode(),
                    value.getGuiFontMode(), value.getCustomMainColor(), value.getCustomAccentColor(),
                    value.getStatisticsDefaultPeriodDays(), value.isCheckedForJsonMigration(),
                    value.isBankPaylogImportOnboardingHandled());
        }
    }

    private ClientConfigManager() {
    }

    public static boolean isAutomaticPaylogDetection() {
        return runtimeConfig().automaticPaylogDetection();
    }

    public static synchronized boolean setAutomaticPaylogDetection(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setAutomaticPaylogDetection(enabled);
        return save(loaded);
    }

    public static boolean isAutoLinkDetectedPaylogsToDeals() {
        return runtimeConfig().autoLinkDetectedPaylogsToDeals();
    }

    public static synchronized boolean setAutoLinkDetectedPaylogsToDeals(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setAutoLinkDetectedPaylogsToDeals(enabled);
        return save(loaded);
    }

    public static boolean isDetectPaylogsInOverlay() {
        return runtimeConfig().detectPaylogsInOverlay();
    }

    public static synchronized boolean setDetectPaylogsInOverlay(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setDetectPaylogsInOverlay(enabled);
        return save(loaded);
    }

    public static boolean isShowPaylogNotifications() {
        return runtimeConfig().showAnyPaylogFlyIns();
    }

    public static synchronized boolean setShowPaylogNotifications(boolean enabled) {
        return setShowAnyPaylogFlyIns(enabled);
    }

    public static boolean isShowAnyPaylogFlyIns() {
        return runtimeConfig().showAnyPaylogFlyIns();
    }

    public static synchronized boolean setShowAnyPaylogFlyIns(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setShowAnyPaylogFlyIns(enabled);
        return save(loaded);
    }

    public static boolean isShowDealDetectionPaylogFlyIns() {
        return runtimeConfig().showDealDetectionPaylogFlyIns();
    }

    public static synchronized boolean setShowDealDetectionPaylogFlyIns(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setShowDealDetectionPaylogFlyIns(enabled);
        return save(loaded);
    }

    public static PaylogAutoLinkMode getPaylogAutoLinkMode() {
        return runtimeConfig().paylogAutoLinkMode();
    }

    public static synchronized boolean setPaylogAutoLinkMode(PaylogAutoLinkMode mode) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setPaylogAutoLinkMode(mode);
        return save(loaded);
    }

    public static boolean isCompleteDealOnPaylogOverpay() {
        return runtimeConfig().completeDealOnPaylogOverpay();
    }

    public static synchronized boolean setCompleteDealOnPaylogOverpay(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCompleteDealOnPaylogOverpay(enabled);
        return save(loaded);
    }

    public static boolean isNotifyWhenPaylogHasMatchingDeal() {
        return runtimeConfig().notifyWhenPaylogHasMatchingDeal();
    }

    public static synchronized boolean setNotifyWhenPaylogHasMatchingDeal(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setNotifyWhenPaylogHasMatchingDeal(enabled);
        return save(loaded);
    }

    public static boolean isNotifyWhenPaylogHasNoMatchingDeal() {
        return runtimeConfig().notifyWhenPaylogHasNoMatchingDeal();
    }

    public static synchronized boolean setNotifyWhenPaylogHasNoMatchingDeal(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setNotifyWhenPaylogHasNoMatchingDeal(enabled);
        return save(loaded);
    }

    public static boolean isNotifyWhenPaylogHasMultipleMatchingDeals() {
        return runtimeConfig().notifyWhenPaylogHasMultipleMatchingDeals();
    }

    public static synchronized boolean setNotifyWhenPaylogHasMultipleMatchingDeals(boolean enabled) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setNotifyWhenPaylogHasMultipleMatchingDeals(enabled);
        return save(loaded);
    }

    public static ModernThemeMode getModernThemeMode() {
        return runtimeConfig().modernThemeMode();
    }

    public static UiRenderConfig uiRenderConfig() {
        return renderConfig;
    }

    public static synchronized boolean setModernThemeMode(ModernThemeMode mode) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setModernThemeMode(mode);
        return save(loaded);
    }

    public static GuiFontMode getGuiFontMode() {
        return runtimeConfig().guiFontMode();
    }

    public static synchronized boolean setGuiFontMode(GuiFontMode mode) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setGuiFontMode(mode);
        return save(loaded);
    }

    public static int getCustomMainColor() {
        return runtimeConfig().customMainColor();
    }

    public static synchronized boolean setCustomMainColor(int color) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCustomMainColor(color);
        return save(loaded);
    }

    public static int getCustomAccentColor() {
        return runtimeConfig().customAccentColor();
    }

    public static synchronized boolean setCustomAccentColor(int color) {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        loaded.setCustomAccentColor(color);
        return save(loaded);
    }

    public static int getStatisticsDefaultPeriodDays() {
        return runtimeConfig().statisticsDefaultPeriodDays();
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

    public static boolean isCheckedForJsonMigration() { return runtimeConfig().checkedForJsonMigration(); }
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

    public static boolean isBankPaylogImportOnboardingPending() {
        RuntimeConfig snapshot = runtimeConfig();
        return !recoveryRequired && !snapshot.bankPaylogImportOnboardingHandled();
    }

    public static synchronized boolean markBankPaylogImportOnboardingHandled() {
        ClientConfig loaded = writableConfig();
        if (loaded == null) return false;
        if (loaded.isBankPaylogImportOnboardingHandled()) return true;
        loaded.setBankPaylogImportOnboardingHandled(true);
        return save(loaded);
    }

    public static boolean isWritable() {
        return !recoveryRequiredAfterLoad();
    }

    public static synchronized void reload() {
        recoveryRequired = true;
        config = null;
        getConfig();
    }

    public static synchronized boolean resetCorruptConfigWithDefaults() {
        getConfig();
        if (!recoveryRequired) return false;
        Path path = FileManager.getClientConfigFile();
        if (!JsonStorage.createBackup(path)) return false;
        ClientConfig defaults = new ClientConfig();
        defaults.normalize();
        if (JsonStorage.save(path, defaults)) {
            config = defaults;
            publishRuntimeConfig(defaults);
            publishRenderConfig(defaults);
            recoveryRequired = false;
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
        if (!FileManager.databaseAccessAllowed()) {
            ClientConfig loaded = new ClientConfig();
            loaded.normalize();
            recoveryRequired = true;
            publishRuntimeConfig(loaded);
            publishRenderConfig(loaded);
            config = loaded;
            return loaded;
        }
        Path path = FileManager.getClientConfigFile();
        JsonStorage.LoadResult<ClientConfig> result = JsonStorage.load(path, ClientConfig.class, new ClientConfig());
        ClientConfig loaded = result.value();
        loaded.normalize();
        recoveryRequired = result.recoveryRequired();
        publishRuntimeConfig(loaded);
        publishRenderConfig(loaded);
        config = loaded;

        if (result.missing()) save(loaded);
        return loaded;
    }

    private static boolean save(ClientConfig value) {
        if (recoveryRequired || !FileManager.databaseAccessAllowed()) return false;
        value.normalize();
        if (JsonStorage.save(FileManager.getClientConfigFile(), value)) {
            config = value;
            publishRuntimeConfig(value);
            publishRenderConfig(value);
            return true;
        }
        config = null;
        getConfig();
        return false;
    }

    private static ClientConfig writableConfig() {
        ClientConfig loaded = getConfig();
        return recoveryRequired ? null : loaded;
    }

    private static RuntimeConfig runtimeConfig() {
        ensureLoaded();
        ClientConfig current = config;
        if (current != null && current != runtimeConfigSource) {
            synchronized (ClientConfigManager.class) {
                current = config;
                if (current != null && current != runtimeConfigSource) {
                    publishRuntimeConfig(current);
                    publishRenderConfig(current);
                }
            }
        }
        return runtimeConfig;
    }

    private static boolean recoveryRequiredAfterLoad() {
        ensureLoaded();
        return recoveryRequired;
    }

    private static void ensureLoaded() {
        if (config != null) return;
        synchronized (ClientConfigManager.class) {
            if (config == null) getConfig();
        }
    }

    private static void publishRuntimeConfig(ClientConfig value) {
        runtimeConfig = RuntimeConfig.from(value);
        runtimeConfigSource = value;
    }

    private static void publishRenderConfig(ClientConfig value) {
        ModernThemeMode mode = value.getModernThemeMode();
        GuiFontMode fontMode = value.getGuiFontMode();
        int mainColor = value.getCustomMainColor();
        int accentColor = value.getCustomAccentColor();
        boolean themeChanged = mode != renderedThemeMode
                || mainColor != renderedMainColor
                || accentColor != renderedAccentColor;
        boolean fontChanged = fontMode != renderedFontMode;
        if (!themeChanged && !fontChanged) return;
        renderedThemeMode = mode;
        renderedFontMode = fontMode;
        renderedMainColor = mainColor;
        renderedAccentColor = accentColor;
        renderEpoch++;
        if (fontChanged) fontEpoch++;
        renderConfig = new UiRenderConfig(
                ModernThemeFactory.create(mode, mainColor, accentColor),
                fontMode, renderEpoch, fontEpoch);
    }
}
