package op.creditmanager.client.config;

import op.creditmanager.client.gui.modern.theme.ModernThemeMode;

/**
 * Small, independent client-only configuration. It deliberately contains no
 * credit data so changing GUI preferences can never modify user records.
 */
public class ClientConfig {

    public static final int CURRENT_VERSION = 3;

    private int configVersion = CURRENT_VERSION;
    private GuiMode guiMode = GuiMode.UNSELECTED;
    private boolean automaticPaylogDetection = true;
    private boolean detectPaylogsInOverlay = true;
    private boolean showPaylogNotifications = false;
    private ModernThemeMode modernThemeMode = ModernThemeMode.DARK;
    private GuiFontMode guiFontMode = GuiFontMode.MOD;
    private int customMainColor = 0xFF1F7A3A;
    private int customAccentColor = 0xFF7EE787;
    private int statisticsDefaultPeriodDays = 30;

    public ClientConfig() {
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public GuiMode getGuiMode() {
        return guiMode;
    }

    public void setGuiMode(GuiMode guiMode) {
        this.guiMode = guiMode == null ? GuiMode.UNSELECTED : guiMode;
    }

    public boolean isAutomaticPaylogDetection() {
        return automaticPaylogDetection;
    }

    public void setAutomaticPaylogDetection(boolean automaticPaylogDetection) {
        this.automaticPaylogDetection = automaticPaylogDetection;
    }

    public boolean isDetectPaylogsInOverlay() {
        return detectPaylogsInOverlay;
    }

    public void setDetectPaylogsInOverlay(boolean detectPaylogsInOverlay) {
        this.detectPaylogsInOverlay = detectPaylogsInOverlay;
    }

    public boolean isShowPaylogNotifications() {
        return showPaylogNotifications;
    }

    public void setShowPaylogNotifications(boolean showPaylogNotifications) {
        this.showPaylogNotifications = showPaylogNotifications;
    }

    public ModernThemeMode getModernThemeMode() {
        return modernThemeMode;
    }

    public void setModernThemeMode(ModernThemeMode modernThemeMode) {
        this.modernThemeMode = modernThemeMode == null ? ModernThemeMode.DARK : modernThemeMode;
    }

    public GuiFontMode getGuiFontMode() {
        return guiFontMode;
    }

    public void setGuiFontMode(GuiFontMode guiFontMode) {
        this.guiFontMode = guiFontMode == null ? GuiFontMode.MOD : guiFontMode;
    }

    public int getCustomMainColor() {
        return customMainColor;
    }

    public void setCustomMainColor(int customMainColor) {
        this.customMainColor = 0xFF000000 | customMainColor & 0x00FFFFFF;
    }

    public int getCustomAccentColor() {
        return customAccentColor;
    }

    public void setCustomAccentColor(int customAccentColor) {
        this.customAccentColor = 0xFF000000 | customAccentColor & 0x00FFFFFF;
    }

    public int getStatisticsDefaultPeriodDays() {
        return statisticsDefaultPeriodDays;
    }

    public void setStatisticsDefaultPeriodDays(int statisticsDefaultPeriodDays) {
        this.statisticsDefaultPeriodDays = statisticsDefaultPeriodDays;
    }

    /** Normalises partially written or older configuration files safely. */
    public void normalize() {
        boolean needsThemeMigration = configVersion < 2;
        configVersion = CURRENT_VERSION;
        if (guiMode == null) {
            guiMode = GuiMode.UNSELECTED;
        }
        if (modernThemeMode == null) {
            modernThemeMode = ModernThemeMode.DARK;
        }
        if (guiFontMode == null) {
            guiFontMode = GuiFontMode.MOD;
        }
        if (needsThemeMigration) {
            customMainColor = 0xFF1F7A3A;
            customAccentColor = 0xFF7EE787;
            modernThemeMode = ModernThemeMode.DARK;
        } else {
            customMainColor = 0xFF000000 | customMainColor & 0x00FFFFFF;
            customAccentColor = 0xFF000000 | customAccentColor & 0x00FFFFFF;
        }
        if (statisticsDefaultPeriodDays != 7 && statisticsDefaultPeriodDays != 30
                && statisticsDefaultPeriodDays != 90 && statisticsDefaultPeriodDays != 0) {
            statisticsDefaultPeriodDays = 30;
        }
    }
}
