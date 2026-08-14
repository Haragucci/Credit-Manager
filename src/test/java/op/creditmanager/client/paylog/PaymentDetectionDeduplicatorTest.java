package op.creditmanager.client.paylog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDetectionDeduplicatorTest {

    @Test
    void suppressesSameSourceEventWithoutSlidingTheCooldown() {
        PaymentDetectionDeduplicator deduplicator = new PaymentDetectionDeduplicator(500L, 8);
        PaymentDetectionEvent event = event("event-1");

        PaymentDetectionDeduplicator.Reservation first = deduplicator.reserve(event, 1_000L);
        assertTrue(first != null);
        deduplicator.commit(first, 1_000L);
        assertFalse(deduplicator.reserve(event, 1_200L) != null);
        assertFalse(deduplicator.reserve(event, 1_400L) != null);
        assertTrue(deduplicator.reserve(event, 1_501L) != null);
    }

    @Test
    void acceptsDistinctIdenticalCallbacksAndRetryAfterRollback() {
        PaymentDetectionDeduplicator deduplicator = new PaymentDetectionDeduplicator(500L, 8);
        PaymentDetectionDeduplicator.Reservation failed = deduplicator.reserve(event("event-1"), 1_000L);
        assertTrue(failed != null);
        deduplicator.rollback(failed);
        assertTrue(deduplicator.reserve(event("event-1"), 1_001L) != null);
        assertTrue(deduplicator.reserve(event("event-2"), 1_001L) != null);
    }

    @Test
    void acceptsSameStableEventIdentityAfterReconnect() {
        PaymentDetectionDeduplicator deduplicator = new PaymentDetectionDeduplicator(500L, 8);
        PaymentDetectionEvent firstConnection = event("event-1", "connection-1");
        PaymentDetectionEvent secondConnection = event("event-1", "connection-2");

        PaymentDetectionDeduplicator.Reservation first = deduplicator.reserve(firstConnection, 1_000L);
        assertTrue(first != null);
        deduplicator.commit(first, 1_000L);
        assertFalse(deduplicator.reserve(firstConnection, 1_001L) != null);
        assertTrue(deduplicator.reserve(secondConnection, 1_001L) != null);
    }

    @Test
    void gameFallbackSuppressesTheSameCallbackButKeepsTwoRealIdenticalPayments() {
        PaymentDetectionDeduplicator deduplicator = new PaymentDetectionDeduplicator(500L, 8);
        DetectedPayment payment = new DetectedPayment("debtor", "creditor", 1_000L,
                "OPSUCHT » Du hast creditor 10$ gegeben.");
        PaymentDetectionEvent first = new PaymentDetectionEvent(payment, "GAME", "connection", "server", 1_000L, null);
        PaymentDetectionEvent sameCallback = new PaymentDetectionEvent(payment, "GAME", "connection", "server", 1_000L, null);
        PaymentDetectionEvent laterPayment = new PaymentDetectionEvent(payment, "GAME", "connection", "server", 1_001L, null);

        PaymentDetectionDeduplicator.Reservation reservation = deduplicator.reserve(first, 1_000L);
        assertTrue(reservation != null);
        deduplicator.commit(reservation, 1_000L);

        assertFalse(deduplicator.reserve(sameCallback, 1_001L) != null);
        assertTrue(deduplicator.reserve(laterPayment, 1_001L) != null);
    }

    private PaymentDetectionEvent event(String id, String connectionId) {
        PaymentDetectionEvent template = event(id);
        return new PaymentDetectionEvent(template.payment(), template.channel(), connectionId, template.serverId(),
                template.receptionTimestamp(), template.stableEventId());
    }

    private PaymentDetectionEvent event(String id) {
        DetectedPayment payment = new DetectedPayment("debtor", "creditor", 1_000L, "OPSUCHT » debtor hat dir 10$ gegeben.");
        return new PaymentDetectionEvent(payment, "CHAT", "connection", "server", 1_000L, id);
    }
}
