package op.creditmanager.client.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoalescingSaveSchedulerTest {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "skin-cache-test");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2L, TimeUnit.SECONDS);
    }

    @Test
    void coalescesPendingRequestsAndFlushesExactlyOneSnapshot() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        CoalescingSaveScheduler scheduler = new CoalescingSaveScheduler(executor, 60_000L, () -> {
            saves.incrementAndGet();
            return true;
        });

        scheduler.request();
        scheduler.request();
        scheduler.request();
        scheduler.flushAsync().get(2L, TimeUnit.SECONDS);

        assertEquals(1, saves.get());
        scheduler.close();
    }

    @Test
    void failedSaveRemainsDirtyForShutdownRetry() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        CoalescingSaveScheduler scheduler = new CoalescingSaveScheduler(executor, 60_000L,
                () -> saves.incrementAndGet() > 1);

        scheduler.request();
        scheduler.flushAsync().get(2L, TimeUnit.SECONDS);
        scheduler.flushAsync().get(2L, TimeUnit.SECONDS);

        assertEquals(2, saves.get());
        scheduler.close();
    }
}
