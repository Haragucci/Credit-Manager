package op.creditmanager.client.paylog;

public record PaymentDetectionEvent(DetectedPayment payment, String channel, String connectionId, String serverId,
                                    long receptionTimestamp, String stableEventId, long clientTick) {
    public PaymentDetectionEvent(DetectedPayment payment, String channel, String connectionId, String serverId,
                                 long receptionTimestamp, String stableEventId) {
        this(payment, channel, connectionId, serverId, receptionTimestamp, stableEventId, -1L);
    }

    public PaymentDetectionEvent {
        channel = channel == null || channel.isBlank() ? "UNKNOWN" : channel;
        receptionTimestamp = receptionTimestamp > 0L ? receptionTimestamp : System.currentTimeMillis();
    }
}
