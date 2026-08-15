package op.creditmanager.client.core;

import op.creditmanager.client.paylog.PaymentDetectionDeduplicator;
import op.creditmanager.client.paylog.PaymentMessageParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentDetectorNoIoForIrrelevantMessageTest {
    @Test
    void tenThousandIrrelevantMessagesPerformNoWriteSafetyOrPersistenceWork() {
        CreditManagerMutationExecutor executor = new CreditManagerMutationExecutor(4, "payment-no-io-test");
        AtomicInteger writableChecks = new AtomicInteger();
        AtomicInteger persistCalls = new AtomicInteger();
        PaymentDetector detector = new PaymentDetector(null, new PaymentMessageParser(),
                new PaymentDetectionDeduplicator(500L, 32), () -> {
                    writableChecks.incrementAndGet();
                    return true;
                }, entry -> {
                    persistCalls.incrementAndGet();
                    return true;
                }, executor, Runnable::run, PaymentDetector.DetectionOptions::quiet, completion -> { });
        try {
            for (int index = 0; index < 10_000; index++) {
                detector.processCaptured(incoming("Normale Servernachricht " + index, index + 1L, "event-" + index));
            }

            assertEquals(0, writableChecks.get());
            assertEquals(0, persistCalls.get());
            assertEquals(0, executor.queueSize());
        } finally {
            executor.shutdownAndAwait(Duration.ofSeconds(2));
        }
    }

    private PaymentDetector.IncomingMessage incoming(String message, long timestamp, String stableId) {
        return new PaymentDetector.IncomingMessage(message, "05Haragucci", "CHAT", "connection", "server",
                timestamp, stableId, timestamp);
    }
}
