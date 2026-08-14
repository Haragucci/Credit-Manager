package op.creditmanager.client.paylog;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.search.SearchNormalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class PaymentDetectionTrace {
    private static final boolean ENABLED = Boolean.getBoolean("creditmanager.dev.paymentTrace");

    private PaymentDetectionTrace() { }

    static void record(PaymentDetectionEvent event, String candidateKey, String decision) {
        if (!ENABLED || event == null || event.payment() == null) return;
        DetectedPayment payment = event.payment();
        CreditManagerClient.LOGGER.info(
                "[PaymentTrace] connection={} serverHash={} channel={} overlay={} normalizedHash={} rawHash={} stablePresent={} stableHash={} receivedAt={} clientTick={} fromHash={} toHash={} amountMinor={} candidateHash={} decision={}",
                safe(event.connectionId()), hash(event.serverId()), safe(event.channel()), "OVERLAY".equals(event.channel()),
                hash(SearchNormalizer.normalize(payment.rawMessage())), hash(payment.rawMessage()),
                event.stableEventId() != null && !event.stableEventId().isBlank(), hash(event.stableEventId()),
                event.receptionTimestamp(), event.clientTick(), hash(payment.fromPlayer()), hash(payment.toPlayer()),
                payment.amountMinor(), hash(candidateKey), safe(decision));
    }

    static String hash(String value) {
        if (value == null || value.isBlank()) return "none";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
