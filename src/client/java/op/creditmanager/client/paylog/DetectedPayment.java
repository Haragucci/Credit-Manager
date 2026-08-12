package op.creditmanager.client.paylog;

public record DetectedPayment(String fromPlayer, String toPlayer, long amountMinor, String rawMessage) { }
