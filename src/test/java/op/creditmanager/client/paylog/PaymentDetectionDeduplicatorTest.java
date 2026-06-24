package op.creditmanager.client.paylog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDetectionDeduplicatorTest {

    @Test
    void suppressesIdenticalPaymentsInsideCooldownAndAcceptsThemAfterward() {
        PaymentDetectionDeduplicator deduplicator = new PaymentDetectionDeduplicator(500L, 8);
        DetectedPayment payment = new DetectedPayment("debtor", "creditor", 10D, "OPSUCHT » debtor hat dir 10$ gegeben.");

        assertTrue(deduplicator.firstSeen(payment, 1_000L));
        assertFalse(deduplicator.firstSeen(payment, 1_200L));
        assertTrue(deduplicator.firstSeen(payment, 1_700L));
    }
}
