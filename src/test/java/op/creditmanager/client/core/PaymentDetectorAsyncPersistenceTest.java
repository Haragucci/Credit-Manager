package op.creditmanager.client.core;

import op.creditmanager.client.paylog.PaymentDetectionDeduplicator;
import op.creditmanager.client.paylog.PaymentMessageParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDetectorAsyncPersistenceTest {
    @Test
    void slowPersistenceDoesNotBlockCaptureAndPreservesFifoOrderAndCompletionBoundary() throws Exception {
        CreditManagerMutationExecutor executor = new CreditManagerMutationExecutor(16, "payment-async-test");
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        CountDownLatch persisted = new CountDownLatch(5);
        List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        List<String> sinkThreads = Collections.synchronizedList(new ArrayList<>());
        Queue<Runnable> clientCompletions = new ConcurrentLinkedQueue<>();
        List<PaymentDetector.ProcessingCompletion> completions = new ArrayList<>();
        PaymentDetector detector = detector(executor, entry -> {
            sinkThreads.add(Thread.currentThread().getName());
            if (timestamps.isEmpty()) {
                sinkEntered.countDown();
                try {
                    releaseSink.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            timestamps.add(entry.getTimestamp());
            persisted.countDown();
            return true;
        }, clientCompletions::add, completions::add);
        try {
            long callerStarted = System.nanoTime();
            detector.processCaptured(incoming(1_001L, "event-1"));
            long callerElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callerStarted);
            assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));
            assertTrue(callerElapsedMillis < 500L);
            for (int index = 2; index <= 5; index++) {
                detector.processCaptured(incoming(1_000L + index, "event-" + index));
            }

            releaseSink.countDown();

            assertTrue(persisted.await(2, TimeUnit.SECONDS));
            awaitQueueSize(clientCompletions, 5);
            String completionThread = Thread.currentThread().getName();
            Runnable completion;
            while ((completion = clientCompletions.poll()) != null) completion.run();
            assertEquals(List.of(1_001L, 1_002L, 1_003L, 1_004L, 1_005L), timestamps);
            assertEquals(5, completions.size());
            assertTrue(completions.stream().allMatch(value -> value.failure() == null
                    && value.result() != null && value.result().persisted()));
            assertTrue(sinkThreads.stream().allMatch("payment-async-test"::equals));
            assertNotEquals(sinkThreads.getFirst(), completionThread);
        } finally {
            releaseSink.countDown();
            executor.shutdownAndAwait(Duration.ofSeconds(2));
        }
    }

    @Test
    void persistenceExceptionRollsBackReservationSoTheSameEventCanBeRetried() throws Exception {
        CreditManagerMutationExecutor executor = new CreditManagerMutationExecutor(4, "payment-rollback-test");
        AtomicInteger sinkCalls = new AtomicInteger();
        CountDownLatch completionsReceived = new CountDownLatch(2);
        List<PaymentDetector.ProcessingCompletion> completions = Collections.synchronizedList(new ArrayList<>());
        PaymentDetector detector = detector(executor, entry -> {
            if (sinkCalls.incrementAndGet() == 1) throw new IllegalStateException("injected");
            return true;
        }, Runnable::run, completion -> {
            completions.add(completion);
            completionsReceived.countDown();
        });
        try {
            PaymentDetector.IncomingMessage incoming = incoming(2_000L, "retry-event");
            detector.processCaptured(incoming);
            awaitSize(completions, 1);
            detector.processCaptured(incoming);

            assertTrue(completionsReceived.await(2, TimeUnit.SECONDS));
            assertEquals(2, sinkCalls.get());
            assertTrue(completions.getFirst().failure() instanceof IllegalStateException);
            assertNull(completions.get(1).failure());
            assertTrue(completions.get(1).result().persisted());
            assertFalse(completions.get(1).result().duplicate());
        } finally {
            executor.shutdownAndAwait(Duration.ofSeconds(2));
        }
    }

    private PaymentDetector detector(CreditManagerMutationExecutor executor, PaymentDetector.PaylogSink sink,
                                     java.util.function.Consumer<Runnable> completionExecutor,
                                     java.util.function.Consumer<PaymentDetector.ProcessingCompletion> completionSink) {
        return new PaymentDetector(null, new PaymentMessageParser(),
                new PaymentDetectionDeduplicator(500L, 32), () -> true, sink, executor, completionExecutor,
                PaymentDetector.DetectionOptions::quiet, completionSink);
    }

    private PaymentDetector.IncomingMessage incoming(long timestamp, String stableId) {
        return new PaymentDetector.IncomingMessage("OPSUCHT » Jerry237 hat dir 10$ gegeben.",
                "05Haragucci", "CHAT", "connection", "server", timestamp, stableId, timestamp);
    }

    private void awaitQueueSize(Queue<?> values, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (values.size() < expected && System.nanoTime() < deadline) Thread.sleep(5L);
        assertEquals(expected, values.size());
    }

    private void awaitSize(List<?> values, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (values.size() < expected && System.nanoTime() < deadline) Thread.sleep(5L);
        assertEquals(expected, values.size());
    }
}
