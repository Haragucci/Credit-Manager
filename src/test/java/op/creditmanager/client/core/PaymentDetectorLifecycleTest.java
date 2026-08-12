package op.creditmanager.client.core;

import op.creditmanager.client.paylog.DetectedPayment;
import op.creditmanager.client.paylog.PaymentDetectionDeduplicator;
import op.creditmanager.client.paylog.PaymentDetectionEvent;
import op.creditmanager.client.paylog.PaymentMessageParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDetectorLifecycleTest {

    @Test
    void recoveryTransitionActivatesWithoutReconstructionAndCapturesTheNextEventExactlyOnce() {
        AtomicBoolean writable = new AtomicBoolean(false);
        AtomicInteger persisted = new AtomicInteger();
        PaymentDetectionDeduplicator deduplicator = new PaymentDetectionDeduplicator(500L, 16);
        PaymentDetector detector = new PaymentDetector(null, new PaymentMessageParser(), deduplicator,
                writable::get, entry -> true);
        PaymentDetectionEvent event = event("event-1");

        assertFalse(detector.isDetectionActive());
        assertEquals(0, persisted.get());

        writable.set(true);
        assertTrue(detector.isDetectionActive());
        PaymentDetectionDeduplicator.Reservation first = deduplicator.reserve(event, 1_000L);
        assertTrue(first != null);
        persisted.incrementAndGet();
        deduplicator.commit(first, 1_000L);

        assertFalse(deduplicator.reserve(event, 1_001L) != null);
        assertEquals(1, persisted.get());
    }

    private PaymentDetectionEvent event(String id) {
        DetectedPayment payment = new DetectedPayment("debtor", "creditor", 1_000L,
                "OPSUCHT payment debtor to creditor 10 dollars");
        return new PaymentDetectionEvent(payment, "CHAT", "connection", "server", 1_000L, id);
    }
}
