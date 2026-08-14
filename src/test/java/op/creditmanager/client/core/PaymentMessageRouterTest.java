package op.creditmanager.client.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentMessageRouterTest {

    @Test
    void chatWithDetectorAndEnabledDetectionProcessesExactlyOnce() {
        RecordingHandler handler = new RecordingHandler();

        assertEquals(PaymentMessageRouter.DispatchStatus.PROCESSED,
                dispatch(handler, true, true, PaymentMessageRouter.MessageSource.CHAT));
        assertEquals(1, handler.messages.size());
        assertEquals("CHAT", handler.messages.getFirst());
    }

    @Test
    void gameWithDetectorAndEnabledDetectionProcessesExactlyOnce() {
        RecordingHandler handler = new RecordingHandler();

        assertEquals(PaymentMessageRouter.DispatchStatus.PROCESSED,
                dispatch(handler, true, true, PaymentMessageRouter.MessageSource.GAME));
        assertEquals(List.of("GAME"), handler.messages);
    }

    @Test
    void disabledDetectionAndDisabledOverlayDoNotProcess() {
        RecordingHandler handler = new RecordingHandler();

        assertEquals(PaymentMessageRouter.DispatchStatus.SKIPPED,
                dispatch(handler, false, true, PaymentMessageRouter.MessageSource.CHAT));
        assertEquals(PaymentMessageRouter.DispatchStatus.SKIPPED,
                dispatch(handler, true, false, PaymentMessageRouter.MessageSource.OVERLAY));
        assertEquals(List.of(), handler.messages);
    }

    @Test
    void missingDetectorDoesNotCrash() {
        assertEquals(PaymentMessageRouter.DispatchStatus.SKIPPED,
                dispatch(null, true, true, PaymentMessageRouter.MessageSource.CHAT));
    }

    @Test
    void detectorExceptionIsReportedWithoutEscapingTheCallbackBoundary() {
        AtomicInteger failures = new AtomicInteger();
        RecordingHandler handler = new RecordingHandler();
        handler.throwOnProcess = true;

        PaymentMessageRouter.DispatchStatus status = PaymentMessageRouter.dispatch(handler, true, true,
                PaymentMessageRouter.MessageSource.CHAT, "payment", 1_000L, "event",
                (operation, exception) -> failures.incrementAndGet());

        assertEquals(PaymentMessageRouter.DispatchStatus.FAILED, status);
        assertEquals(1, failures.get());
    }

    @Test
    void joinUsesNormalizedServerIdentity() {
        RecordingHandler handler = new RecordingHandler();

        assertEquals(PaymentMessageRouter.DispatchStatus.PROCESSED,
                PaymentMessageRouter.rotateContext(handler,
                        PaymentMessageRouter.serverIdentity(false, " play.example.test "), null));
        assertEquals(List.of("play.example.test"), handler.contexts);
        assertEquals("singleplayer", PaymentMessageRouter.serverIdentity(true, "ignored"));
        assertEquals("unknown", PaymentMessageRouter.serverIdentity(false, null));
    }

    @Test
    void disconnectRotatesToDisconnectedContext() {
        RecordingHandler handler = new RecordingHandler();

        PaymentMessageRouter.rotateContext(handler, "disconnected", null);

        assertEquals(List.of("disconnected"), handler.contexts);
    }

    @Test
    void reconnectToSameServerRotatesEveryConnection() {
        RecordingHandler handler = new RecordingHandler();

        PaymentMessageRouter.rotateContext(handler, "play.example.test", null);
        PaymentMessageRouter.rotateContext(handler, "disconnected", null);
        PaymentMessageRouter.rotateContext(handler, "play.example.test", null);

        assertEquals(List.of("play.example.test", "disconnected", "play.example.test"), handler.contexts);
    }

    @Test
    void serverSwitchDoesNotReuseThePreviousContext() {
        RecordingHandler handler = new RecordingHandler();

        PaymentMessageRouter.rotateContext(handler, "server-a.example", null);
        PaymentMessageRouter.rotateContext(handler, "server-b.example", null);

        assertEquals(List.of("server-a.example", "server-b.example"), handler.contexts);
    }

    private PaymentMessageRouter.DispatchStatus dispatch(PaymentMessageRouter.Handler handler, boolean enabled,
                                                         boolean overlayEnabled,
                                                         PaymentMessageRouter.MessageSource source) {
        return PaymentMessageRouter.dispatch(handler, enabled, overlayEnabled, source,
                "payment", 1_000L, "event", null);
    }

    private static final class RecordingHandler implements PaymentMessageRouter.Handler {
        private final List<String> messages = new ArrayList<>();
        private final List<String> contexts = new ArrayList<>();
        private boolean throwOnProcess;

        @Override
        public void process(String message, String channel, long receptionTimestamp, String stableEventId) {
            if (throwOnProcess) throw new IllegalStateException("injected");
            messages.add(channel);
        }

        @Override
        public void rotateConnectionContext(String serverIdentity) {
            contexts.add(serverIdentity);
        }
    }
}
