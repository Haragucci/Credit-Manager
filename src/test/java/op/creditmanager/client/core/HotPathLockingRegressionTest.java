package op.creditmanager.client.core;

import org.junit.jupiter.api.Test;
import op.creditmanager.client.config.ClientConfigManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;

class HotPathLockingRegressionTest {
    @Test
    void clientHotPathReadsDoNotShareRepositoryOrConfigWriteMonitors() throws Exception {
        assertNotSynchronized(CreditRepository.class, "isWritable");
        assertNotSynchronized(CreditRepository.class, "snapshotOpenCredits", String.class);
        assertNotSynchronized(CreditRepository.class, "getRecoveryRecords");

        assertNotSynchronized(TransactionRepository.class, "isWritable");
        assertNotSynchronized(TransactionRepository.class, "getRevision");

        assertNotSynchronized(CreditEventRepository.class, "isWritable");
        assertNotSynchronized(CreditEventRepository.class, "getRevision");
        assertNotSynchronized(CreditEventRepository.class, "getRecentEvents");

        assertNotSynchronized(ClientConfigManager.class, "isWritable");
        assertNotSynchronized(ClientConfigManager.class, "isBankPaylogImportOnboardingPending");
        assertNotSynchronized(ClientConfigManager.class, "isAutomaticPaylogDetection");
        assertNotSynchronized(ClientConfigManager.class, "isDetectPaylogsInOverlay");
        assertNotSynchronized(ClientConfigManager.class, "isShowAnyPaylogFlyIns");
        assertNotSynchronized(ClientConfigManager.class, "isShowDealDetectionPaylogFlyIns");
        assertNotSynchronized(ClientConfigManager.class, "getPaylogAutoLinkMode");
        assertNotSynchronized(ClientConfigManager.class, "isCompleteDealOnPaylogOverpay");
        assertNotSynchronized(ClientConfigManager.class, "isNotifyWhenPaylogHasMatchingDeal");
        assertNotSynchronized(ClientConfigManager.class, "isNotifyWhenPaylogHasNoMatchingDeal");
        assertNotSynchronized(ClientConfigManager.class, "isNotifyWhenPaylogHasMultipleMatchingDeals");
        assertNotSynchronized(ClientConfigManager.class, "getModernThemeMode");
        assertNotSynchronized(ClientConfigManager.class, "getGuiFontMode");
        assertNotSynchronized(ClientConfigManager.class, "getCustomMainColor");
        assertNotSynchronized(ClientConfigManager.class, "getCustomAccentColor");
        assertNotSynchronized(ClientConfigManager.class, "getStatisticsDefaultPeriodDays");
    }

    private static void assertNotSynchronized(Class<?> type, String methodName, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, parameters);
        assertFalse(Modifier.isSynchronized(method.getModifiers()),
                () -> type.getSimpleName() + '.' + methodName + " must stay lock-free on client hot paths");
    }
}
