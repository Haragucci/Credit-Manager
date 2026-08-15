package op.creditmanager.client.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditManagerMutationExecutorTest {
    @Test
    void acceptedMutationsRunInFifoOrderOnOneDaemonWorker() throws Exception {
        CreditManagerMutationExecutor executor = new CreditManagerMutationExecutor(4, "mutation-fifo-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            CompletableFuture<Integer> first = executor.submit(() -> {
                worker.set(Thread.currentThread());
                firstStarted.countDown();
                releaseFirst.await();
                order.add(1);
                return 1;
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<Integer> second = executor.submit(() -> {
                order.add(2);
                return 2;
            });
            CompletableFuture<Integer> third = executor.submit(() -> {
                order.add(3);
                return 3;
            });

            releaseFirst.countDown();

            assertEquals(1, first.get(2, TimeUnit.SECONDS).intValue());
            assertEquals(2, second.get(2, TimeUnit.SECONDS).intValue());
            assertEquals(3, third.get(2, TimeUnit.SECONDS).intValue());
            assertEquals(List.of(1, 2, 3), order);
            assertEquals("mutation-fifo-test", worker.get().getName());
            assertTrue(worker.get().isDaemon());
        } finally {
            releaseFirst.countDown();
            executor.shutdownAndAwait(Duration.ofSeconds(2));
        }
    }

    @Test
    void boundedQueueRejectsVisiblyWithoutRunningMutationOnCaller() throws Exception {
        CreditManagerMutationExecutor executor = new CreditManagerMutationExecutor(1, "mutation-bound-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean rejectedOperationRan = new AtomicBoolean();
        try {
            CompletableFuture<Void> first = executor.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                return null;
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> queued = executor.submit(() -> null);
            CompletableFuture<Void> rejected = executor.submit(() -> {
                rejectedOperationRan.set(true);
                return null;
            });

            assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
            assertFalse(rejectedOperationRan.get());
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownAndAwait(Duration.ofSeconds(2));
        }
    }

    @Test
    void stopAcceptingDrainsAcceptedWorkAndRejectsNewWork() throws Exception {
        CreditManagerMutationExecutor executor = new CreditManagerMutationExecutor(2, "mutation-drain-test");
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Integer> first = executor.submit(() -> {
            order.add(1);
            return 1;
        });
        CompletableFuture<Integer> second = executor.submit(() -> {
            order.add(2);
            return 2;
        });

        executor.stopAccepting();
        CompletableFuture<Integer> rejected = executor.submit(() -> 3);

        assertEquals(1, first.get(2, TimeUnit.SECONDS).intValue());
        assertEquals(2, second.get(2, TimeUnit.SECONDS).intValue());
        assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
        assertTrue(executor.shutdownAndAwait(Duration.ofSeconds(2)));
        assertEquals(List.of(1, 2), order);
        assertFalse(executor.isAccepting());
        assertTrue(executor.isIdle());
    }
}
