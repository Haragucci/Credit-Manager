package op.creditmanager.client.config;

import op.creditmanager.client.gui.modern.theme.ModernThemeMode;

public class ClientConfig {

    public static final int CURRENT_VERSION = 7;

    private int configVersion = CURRENT_VERSION;
    private boolean automaticPaylogDetection = true;
    private boolean autoLinkDetectedPaylogsToDeals;
    private boolean detectPaylogsInOverlay = true;
    private boolean showPaylogNotifications = false;
    private boolean showAnyPaylogFlyIns = false;
    private boolean showDealDetectionPaylogFlyIns = true;
    private PaylogAutoLinkMode paylogAutoLinkMode = PaylogAutoLinkMode.EXACT_OR_PARTIAL;
    private boolean completeDealOnPaylogOverpay = false;
    private boolean notifyWhenPaylogHasMatchingDeal = true;
    private boolean notifyWhenPaylogHasNoMatchingDeal = false;
    private boolean notifyWhenPaylogHasMultipleMatchingDeals = true;
    private ModernThemeMode modernThemeMode = ModernThemeMode.DARK;
    private GuiFontMode guiFontMode = GuiFontMode.MOD;
    private int customMainColor = 0xFF1F7A3A;
    private int customAccentColor = 0xFF7EE787;
    private int statisticsDefaultPeriodDays = 30;
    private boolean checkedForJsonMigration;
    private boolean jsonMigrationCompleted;
    private int jsonMigrationVersion;

    public ClientConfig() {
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public boolean isAutomaticPaylogDetection() {
        return automaticPaylogDetection;
    }

    public void setAutomaticPaylogDetection(boolean automaticPaylogDetection) {
        this.automaticPaylogDetection = automaticPaylogDetection;
    }

    public boolean isAutoLinkDetectedPaylogsToDeals() {
        return getPaylogAutoLinkMode() != PaylogAutoLinkMode.OFF;
    }

    public void setAutoLinkDetectedPaylogsToDeals(boolean autoLinkDetectedPaylogsToDeals) {
        this.autoLinkDetectedPaylogsToDeals = autoLinkDetectedPaylogsToDeals;
        paylogAutoLinkMode = autoLinkDetectedPaylogsToDeals ? PaylogAutoLinkMode.EXACT_OR_PARTIAL : PaylogAutoLinkMode.OFF;
    }

    public boolean isDetectPaylogsInOverlay() {
        return detectPaylogsInOverlay;
    }

    public void setDetectPaylogsInOverlay(boolean detectPaylogsInOverlay) {
        this.detectPaylogsInOverlay = detectPaylogsInOverlay;
    }

    public boolean isShowPaylogNotifications() {
        return showAnyPaylogFlyIns;
    }

    public void setShowPaylogNotifications(boolean showPaylogNotifications) {
        this.showPaylogNotifications = showPaylogNotifications;
        this.showAnyPaylogFlyIns = showPaylogNotifications;
    }

    public boolean isShowAnyPaylogFlyIns() {
        return showAnyPaylogFlyIns;
    }

    public void setShowAnyPaylogFlyIns(boolean showAnyPaylogFlyIns) {
        this.showAnyPaylogFlyIns = showAnyPaylogFlyIns;
        this.showPaylogNotifications = showAnyPaylogFlyIns;
    }

    public boolean isShowDealDetectionPaylogFlyIns() {
        return showDealDetectionPaylogFlyIns;
    }

    public void setShowDealDetectionPaylogFlyIns(boolean showDealDetectionPaylogFlyIns) {
        this.showDealDetectionPaylogFlyIns = showDealDetectionPaylogFlyIns;
    }

    public PaylogAutoLinkMode getPaylogAutoLinkMode() {
        return paylogAutoLinkMode == null ? PaylogAutoLinkMode.OFF : paylogAutoLinkMode;
    }

    public void setPaylogAutoLinkMode(PaylogAutoLinkMode paylogAutoLinkMode) {
        this.paylogAutoLinkMode = paylogAutoLinkMode == null ? PaylogAutoLinkMode.OFF : paylogAutoLinkMode;
        this.autoLinkDetectedPaylogsToDeals = this.paylogAutoLinkMode != PaylogAutoLinkMode.OFF;
    }

    public boolean isCompleteDealOnPaylogOverpay() {
        return completeDealOnPaylogOverpay;
    }

    public void setCompleteDealOnPaylogOverpay(boolean completeDealOnPaylogOverpay) {
        this.completeDealOnPaylogOverpay = completeDealOnPaylogOverpay;
    }

    public boolean isNotifyWhenPaylogHasMatchingDeal() {
        return notifyWhenPaylogHasMatchingDeal;
    }

    public void setNotifyWhenPaylogHasMatchingDeal(boolean notifyWhenPaylogHasMatchingDeal) {
        this.notifyWhenPaylogHasMatchingDeal = notifyWhenPaylogHasMatchingDeal;
    }

    public boolean isNotifyWhenPaylogHasNoMatchingDeal() {
        return notifyWhenPaylogHasNoMatchingDeal;
    }

    public void setNotifyWhenPaylogHasNoMatchingDeal(boolean notifyWhenPaylogHasNoMatchingDeal) {
        this.notifyWhenPaylogHasNoMatchingDeal = notifyWhenPaylogHasNoMatchingDeal;
    }

    public boolean isNotifyWhenPaylogHasMultipleMatchingDeals() {
        return notifyWhenPaylogHasMultipleMatchingDeals;
    }

    public void setNotifyWhenPaylogHasMultipleMatchingDeals(boolean notifyWhenPaylogHasMultipleMatchingDeals) {
        this.notifyWhenPaylogHasMultipleMatchingDeals = notifyWhenPaylogHasMultipleMatchingDeals;
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

    public boolean isCheckedForJsonMigration() { return checkedForJsonMigration; }
    public void setCheckedForJsonMigration(boolean checkedForJsonMigration) { this.checkedForJsonMigration = checkedForJsonMigration; }
    public boolean isJsonMigrationCompleted() { return jsonMigrationCompleted; }
    public void setJsonMigrationCompleted(boolean jsonMigrationCompleted) { this.jsonMigrationCompleted = jsonMigrationCompleted; }
    public int getJsonMigrationVersion() { return jsonMigrationVersion; }
    public void setJsonMigrationVersion(int jsonMigrationVersion) { this.jsonMigrationVersion = jsonMigrationVersion; }

    public void normalize() {
        boolean needsThemeMigration = configVersion < 2;
        boolean needsPaylogSettingsMigration = configVersion < 7;
        if (needsPaylogSettingsMigration) {
            showAnyPaylogFlyIns = showPaylogNotifications;
            showDealDetectionPaylogFlyIns = autoLinkDetectedPaylogsToDeals || showPaylogNotifications;
            notifyWhenPaylogHasMatchingDeal = true;
            notifyWhenPaylogHasNoMatchingDeal = false;
            notifyWhenPaylogHasMultipleMatchingDeals = true;
            paylogAutoLinkMode = autoLinkDetectedPaylogsToDeals
                    ? PaylogAutoLinkMode.EXACT_OR_PARTIAL
                    : PaylogAutoLinkMode.OFF;
            completeDealOnPaylogOverpay = false;
        }
        if (paylogAutoLinkMode == null) {
            paylogAutoLinkMode = PaylogAutoLinkMode.OFF;
        }
        autoLinkDetectedPaylogsToDeals = paylogAutoLinkMode != PaylogAutoLinkMode.OFF;
        showPaylogNotifications = showAnyPaylogFlyIns;
        configVersion = CURRENT_VERSION;
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
        if (jsonMigrationVersion < 0) jsonMigrationVersion = 0;
    }
}
