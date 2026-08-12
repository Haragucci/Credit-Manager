package op.creditmanager.client.paylog;

public record PaymentDetectionEvent(DetectedPayment payment, String channel, String connectionId, String serverId,
                                    long receptionTimestamp, String stableEventId) {
    public PaymentDetectionEvent {
        channel = channel == null || channel.isBlank() ? "UNKNOWN" : channel;
        receptionTimestamp = receptionTimestamp > 0L ? receptionTimestamp : System.currentTimeMillis();
    }
}
