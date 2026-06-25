package op.creditmanager.client.paylog;

import op.creditmanager.client.search.SearchNormalizer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PaymentDetectionDeduplicator {
    private final long cooldownMillis;
    private final int maxEntries;
    private final Map<String, Long> recent = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> entry) {
            return size() > maxEntries;
        }
    };

    public PaymentDetectionDeduplicator(long cooldownMillis, int maxEntries) {
        this.cooldownMillis = cooldownMillis;
        this.maxEntries = maxEntries;
    }

    public boolean firstSeen(DetectedPayment payment, long now) {
        String key = payment.fromPlayer() + "->" + payment.toPlayer() + ':' + payment.amount() + ':' + SearchNormalizer.normalize(payment.rawMessage());
        Long previous = recent.put(key, now);
        return previous == null || now - previous >= cooldownMillis;
    }
}
