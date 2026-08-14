package op.creditmanager.client.paylog;

import op.creditmanager.client.search.SearchNormalizer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PaymentDetectionDeduplicator {
    private final long cooldownMillis;
    private final int maxEntries;
    private final Map<String, Long> committed = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> entry) {
            return size() > maxEntries;
        }
    };
    private final Set<String> reserved = new HashSet<>();
    private String connectionNamespace = "disconnected";

    public PaymentDetectionDeduplicator(long cooldownMillis, int maxEntries) {
        if (cooldownMillis < 0L || maxEntries < 1) throw new IllegalArgumentException();
        this.cooldownMillis = cooldownMillis;
        this.maxEntries = maxEntries;
    }

    public synchronized Reservation reserve(PaymentDetectionEvent event, long now) {
        if (event == null || event.payment() == null) return null;
        String key = key(event);
        if (reserved.contains(key)) {
            PaymentDetectionTrace.record(event, key, "DUPLICATE_RESERVED");
            return null;
        }
        Long previous = committed.get(key);
        if (previous != null && now - previous < cooldownMillis) {
            PaymentDetectionTrace.record(event, key, "DUPLICATE_COMMITTED");
            return null;
        }
        reserved.add(key);
        PaymentDetectionTrace.record(event, key, "RESERVED");
        return new Reservation(UUID.randomUUID(), key);
    }

    public synchronized void commit(Reservation reservation, long now) {
        if (reservation == null || !reserved.remove(reservation.key())) return;
        committed.put(reservation.key(), now);
        evictExpired(now);
    }

    public synchronized void rollback(Reservation reservation) {
        if (reservation != null) reserved.remove(reservation.key());
    }

    public synchronized void rotateConnection(String namespace) {
        connectionNamespace = namespace == null || namespace.isBlank() ? UUID.randomUUID().toString() : namespace;
        committed.clear();
        reserved.clear();
    }

    private String key(PaymentDetectionEvent event) {
        String namespace = event.connectionId() == null || event.connectionId().isBlank()
                ? connectionNamespace : event.connectionId();
        if (event.stableEventId() != null && !event.stableEventId().isBlank()) {
            return namespace + "|stable|" + event.stableEventId();
        }
        DetectedPayment payment = event.payment();
        return namespace + '|' + event.channel() + '|' + event.receptionTimestamp() + '|'
                + payment.fromPlayer() + "->" + payment.toPlayer() + ':' + payment.amountMinor() + ':'
                + SearchNormalizer.normalize(payment.rawMessage());
    }

    private void evictExpired(long now) {
        committed.entrySet().removeIf(entry -> now - entry.getValue() >= cooldownMillis);
    }

    public record Reservation(UUID id, String key) { }
}
