package op.creditmanager.client.paylog.importer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPaylogImportOnboardingPolicyTest {
    @Test
    void menuOfferRequiresEverySafeImportPrerequisite() {
        assertTrue(BankPaylogImportController.shouldOfferOnboarding(true, true, true, true, true, false));
        for (int missing = 0; missing < 5; missing++) {
            boolean[] prerequisites = {true, true, true, true, true};
            prerequisites[missing] = false;
            assertFalse(BankPaylogImportController.shouldOfferOnboarding(prerequisites[0], prerequisites[1],
                    prerequisites[2], prerequisites[3], prerequisites[4], false));
        }
        assertFalse(BankPaylogImportController.shouldOfferOnboarding(true, true, true, true, true, true));
    }
}
