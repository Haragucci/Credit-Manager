package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.ProcessStorageLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownStorageLeaseSafetyTest {
    @TempDir Path temporary;

    @Test
    void leaseIsRetainedWhenCriticalCheckpointOutlivesShutdownTimeout() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configurePrimary(temporary, temporary.resolveSibling("shutdown-mirror"));
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            BackupCheckpointService service = new BackupCheckpointService(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    throw new AssertionError("critical checkpoint was interrupted", exception);
                }
                return BackupCheckpointService.CheckpointResult.success(1L, System.currentTimeMillis());
            }, ignored -> { });
            service.seed(1L, 0L, System.currentTimeMillis(), 0L, System.currentTimeMillis(), true);
            service.request(1L);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            DatabaseManager manager = new DatabaseManager(new DatabaseCoordinator());
            Field checkpoint = DatabaseManager.class.getDeclaredField("checkpointService");
            checkpoint.setAccessible(true);
            checkpoint.set(manager, service);

            assertFalse(manager.shutdown(Duration.ofMillis(25)));
            assertTrue(FileManager.hasPrimaryStorageLease());
            assertTrue(ProcessStorageLease.tryAcquire(temporary).isEmpty());

            release.countDown();
            assertTrue(service.awaitIdle(Duration.ofSeconds(5)));
            assertTrue(manager.shutdown(Duration.ofSeconds(1)));
            assertFalse(FileManager.hasPrimaryStorageLease());
            try (ProcessStorageLease takeover = ProcessStorageLease.tryAcquire(temporary).orElseThrow()) {
                assertTrue(takeover.isHeld());
            }
        }
    }
}
