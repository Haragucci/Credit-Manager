package op.creditmanager.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigMigrationTest {

    @TempDir
    Path dataDirectory;

    @Test
    void versionSixPaylogNotificationSettingMigratesToBothFlyInSettings() throws Exception {
        ClientConfig config = new ClientConfig();
        setField(config, "configVersion", 6);
        setField(config, "showPaylogNotifications", true);
        setField(config, "showAnyPaylogFlyIns", false);
        setField(config, "showDealDetectionPaylogFlyIns", false);

        config.normalize();

        assertEquals(ClientConfig.CURRENT_VERSION, config.getConfigVersion());
        assertTrue(config.isShowAnyPaylogFlyIns());
        assertTrue(config.isShowDealDetectionPaylogFlyIns());
    }

    @Test
    void versionSixAutoLinkSettingMigratesToExactOrPartialMode() throws Exception {
        ClientConfig config = new ClientConfig();
        setField(config, "configVersion", 6);
        setField(config, "autoLinkDetectedPaylogsToDeals", true);

        config.normalize();

        assertEquals(PaylogAutoLinkMode.EXACT_OR_PARTIAL, config.getPaylogAutoLinkMode());
        assertTrue(config.isAutoLinkDetectedPaylogsToDeals());
        assertFalse(config.isCompleteDealOnPaylogOverpay());
    }

    @Test
    void nullAutoLinkModeIsNormalizedToSafeOffState() throws Exception {
        ClientConfig config = new ClientConfig();
        setField(config, "paylogAutoLinkMode", null);

        config.normalize();

        assertEquals(PaylogAutoLinkMode.OFF, config.getPaylogAutoLinkMode());
        assertFalse(config.isAutoLinkDetectedPaylogsToDeals());
    }

    @Test
    void currentPaylogSettingsRemainStableAfterNormalization() {
        ClientConfig config = new ClientConfig();
        config.setShowAnyPaylogFlyIns(true);
        config.setShowDealDetectionPaylogFlyIns(false);
        config.setPaylogAutoLinkMode(PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE);
        config.setCompleteDealOnPaylogOverpay(true);
        config.setNotifyWhenPaylogHasMatchingDeal(false);
        config.setNotifyWhenPaylogHasNoMatchingDeal(true);
        config.setNotifyWhenPaylogHasMultipleMatchingDeals(false);

        config.normalize();

        assertTrue(config.isShowAnyPaylogFlyIns());
        assertFalse(config.isShowDealDetectionPaylogFlyIns());
        assertEquals(PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE, config.getPaylogAutoLinkMode());
        assertTrue(config.isCompleteDealOnPaylogOverpay());
        assertFalse(config.isNotifyWhenPaylogHasMatchingDeal());
        assertTrue(config.isNotifyWhenPaylogHasNoMatchingDeal());
        assertFalse(config.isNotifyWhenPaylogHasMultipleMatchingDeals());
        assertNotNull(config.getPaylogAutoLinkMode());
    }

    @Test
    void newVersionEightConfigKeepsBankImportOnboardingPending() {
        ClientConfig config = new ClientConfig();

        config.normalize();

        assertEquals(8, config.getConfigVersion());
        assertFalse(config.isBankPaylogImportOnboardingHandled());
    }

    @Test
    void versionSevenUpgradeMarksBankImportOnboardingHandled() throws Exception {
        ClientConfig config = new ClientConfig();
        setField(config, "configVersion", 7);
        setField(config, "bankPaylogImportOnboardingHandled", false);

        config.normalize();

        assertEquals(8, config.getConfigVersion());
        assertTrue(config.isBankPaylogImportOnboardingHandled());
    }

    @Test
    void paylogSettingsPersistAfterManagerReload() throws Exception {
        Files.createDirectories(dataDirectory);
        Field directoryField = FileManager.class.getDeclaredField("dataDirectory");
        Field configField = ClientConfigManager.class.getDeclaredField("config");
        Field recoveryField = ClientConfigManager.class.getDeclaredField("recoveryRequired");
        directoryField.setAccessible(true);
        configField.setAccessible(true);
        recoveryField.setAccessible(true);
        Path previousDirectory = (Path) directoryField.get(null);
        Object previousConfig = configField.get(null);
        boolean previousRecoveryRequired = recoveryField.getBoolean(null);
        try {
            directoryField.set(null, dataDirectory);
            configField.set(null, null);
            recoveryField.setBoolean(null, false);
            assertTrue(ClientConfigManager.setShowAnyPaylogFlyIns(true));
            assertTrue(ClientConfigManager.setShowDealDetectionPaylogFlyIns(false));
            assertTrue(ClientConfigManager.setPaylogAutoLinkMode(PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE));
            assertTrue(ClientConfigManager.setCompleteDealOnPaylogOverpay(true));
            assertTrue(ClientConfigManager.setNotifyWhenPaylogHasNoMatchingDeal(true));

            ClientConfigManager.reload();

            assertTrue(ClientConfigManager.isShowAnyPaylogFlyIns());
            assertFalse(ClientConfigManager.isShowDealDetectionPaylogFlyIns());
            assertEquals(PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE, ClientConfigManager.getPaylogAutoLinkMode());
            assertTrue(ClientConfigManager.isCompleteDealOnPaylogOverpay());
            assertTrue(ClientConfigManager.isNotifyWhenPaylogHasNoMatchingDeal());
        } finally {
            directoryField.set(null, previousDirectory);
            configField.set(null, previousConfig);
            recoveryField.setBoolean(null, previousRecoveryRequired);
        }
    }

    @Test
    void handledBankImportOnboardingPersistsAfterManagerReload() throws Exception {
        Files.createDirectories(dataDirectory);
        Field directoryField = FileManager.class.getDeclaredField("dataDirectory");
        Field configField = ClientConfigManager.class.getDeclaredField("config");
        Field recoveryField = ClientConfigManager.class.getDeclaredField("recoveryRequired");
        directoryField.setAccessible(true);
        configField.setAccessible(true);
        recoveryField.setAccessible(true);
        Path previousDirectory = (Path) directoryField.get(null);
        Object previousConfig = configField.get(null);
        boolean previousRecoveryRequired = recoveryField.getBoolean(null);
        try {
            directoryField.set(null, dataDirectory);
            configField.set(null, null);
            recoveryField.setBoolean(null, false);

            assertTrue(ClientConfigManager.isBankPaylogImportOnboardingPending());
            assertTrue(ClientConfigManager.markBankPaylogImportOnboardingHandled());
            ClientConfigManager.reload();

            assertFalse(ClientConfigManager.isBankPaylogImportOnboardingPending());
        } finally {
            directoryField.set(null, previousDirectory);
            configField.set(null, previousConfig);
            recoveryField.setBoolean(null, previousRecoveryRequired);
        }
    }

    private void setField(ClientConfig config, String name, Object value) throws Exception {
        Field field = ClientConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }
}
