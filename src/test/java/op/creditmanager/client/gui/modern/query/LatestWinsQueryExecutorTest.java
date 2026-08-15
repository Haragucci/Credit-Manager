package op.creditmanager.client.gui.modern.query;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestWinsQueryExecutorTest {
    @Test
    void rapidReplacementRunsOnlyTheRunningAndLatestQuery() throws Exception {
        LatestWinsQueryExecutor executor = new LatestWinsQueryExecutor(1, "latest-query-test");
        Object owner = new Object();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> executed = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Integer>> replaced = new ArrayList<>();
        try {
            CompletableFuture<Integer> first = executor.submitLatest(owner, () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                executed.add(0);
                return 0;
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            for (int value = 1; value <= 100; value++) {
                int captured = value;
                replaced.add(executor.submitLatest(owner, () -> {
                    executed.add(captured);
                    return captured;
                }));
                assertTrue(executor.pendingCount() <= 1);
            }

            releaseFirst.countDown();

            assertTrue(first.isCancelled());
            assertEquals(100, replaced.getLast().get(2, TimeUnit.SECONDS).intValue());
            assertEquals(List.of(0, 100), executed);
            for (int index = 0; index < replaced.size() - 1; index++) {
                assertTrue(replaced.get(index).isCancelled());
            }
        } finally {
            releaseFirst.countDown();
            executor.shutdownAndAwait(Duration.ofSeconds(2));
        }
    }

    @Test
    void cancelRemovesQueuedObsoleteQuery() throws Exception {
        LatestWinsQueryExecutor executor = new LatestWinsQueryExecutor(1, "latest-cancel-test");
        Object runningOwner = new Object();
        Object queuedOwner = new Object();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submitLatest(runningOwner, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.submitLatest(queuedOwner, () -> 2);

            executor.cancel(queuedOwner);

            assertTrue(queued.isCancelled());
            assertEquals(0, executor.pendingCount());
        } finally {
            release.countDown();
            executor.shutdownAndAwait(Duration.ofSeconds(2));
        }
    }
}
