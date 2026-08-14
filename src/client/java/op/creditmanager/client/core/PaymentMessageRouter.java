package op.creditmanager.client.core;

public final class PaymentMessageRouter {
    private PaymentMessageRouter() { }

    public static DispatchStatus dispatch(Handler handler, boolean automaticDetectionEnabled,
                                          boolean overlayDetectionEnabled, MessageSource source,
                                          String message, long receptionTimestamp, String stableEventId,
                                          ErrorReporter errors) {
        if (handler == null || source == null || message == null || message.isBlank()
                || !automaticDetectionEnabled || source == MessageSource.OVERLAY && !overlayDetectionEnabled) {
            return DispatchStatus.SKIPPED;
        }
        try {
            handler.process(message, source.channel(), receptionTimestamp, stableEventId);
            return DispatchStatus.PROCESSED;
        } catch (RuntimeException exception) {
            report(errors, "incoming " + source.channel() + " payment message", exception);
            return DispatchStatus.FAILED;
        }
    }

    public static DispatchStatus rotateContext(Handler handler, String serverIdentity, ErrorReporter errors) {
        if (handler == null) return DispatchStatus.SKIPPED;
        try {
            handler.rotateConnectionContext(normalizeServerIdentity(serverIdentity));
            return DispatchStatus.PROCESSED;
        } catch (RuntimeException exception) {
            report(errors, "payment detection connection context", exception);
            return DispatchStatus.FAILED;
        }
    }

    public static String serverIdentity(boolean singleplayer, String serverAddress) {
        return singleplayer ? "singleplayer" : normalizeServerIdentity(serverAddress);
    }

    private static String normalizeServerIdentity(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static void report(ErrorReporter errors, String operation, RuntimeException exception) {
        if (errors != null) errors.report(operation, exception);
    }

    public enum MessageSource {
        CHAT,
        GAME,
        OVERLAY;

        public String channel() {
            return name();
        }
    }

    public enum DispatchStatus { SKIPPED, PROCESSED, FAILED }

    public interface Handler {
        void process(String message, String channel, long receptionTimestamp, String stableEventId);
        void rotateConnectionContext(String serverIdentity);
    }

    @FunctionalInterface
    public interface ErrorReporter {
        void report(String operation, RuntimeException exception);
    }
}
