package op.creditmanager.client.paylog;

public record DetectedPayment(String fromPlayer, String toPlayer, double amount, String rawMessage) { }
