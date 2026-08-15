package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupCoordinatorContentionTest {
    @TempDir Path temporary;

    @Test
    void slowArtifactPublicationDoesNotBlockRuntimeStateOrDatabaseWritesAndSerializesPublishers() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            BlockingManifestFileOps fileOps = new BlockingManifestFileOps();
            DatabaseCoordinator coordinator = new DatabaseCoordinator(DatabaseFaultInjector.NONE, fileOps);
            coordinator.initialize();
            assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(
                    credit(new UUID(81L, 1L), "before-backup"), List.of(), List.of(), List.of())));

            CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(coordinator::createBackup);
            assertTrue(fileOps.artifactPhase.await(10, TimeUnit.SECONDS));
            CompletableFuture<Boolean> second;
            try {
                long opened = coordinator.openedConnectionCount();
                assertTrue(coordinator.isHealthy());
                assertTrue(coordinator.isSafeForWrites());
                assertEquals(1L, coordinator.revision());
                assertEquals(opened, coordinator.openedConnectionCount());

                assertTrue(coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(
                        credit(new UUID(81L, 2L), "during-artifact-phase"), List.of(), List.of(), List.of())));
                second = CompletableFuture.supplyAsync(coordinator::createBackup);
                assertFalse(second.isDone());
                assertEquals(1, fileOps.manifestWrites.get());
            } finally {
                fileOps.release.countDown();
            }
            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertTrue(second.get(20, TimeUnit.SECONDS));
            assertEquals(2, coordinator.listBackups().size());
            assertEquals(2L, coordinator.revision());
        }
    }

    private CreditEntry credit(UUID id, String note) {
        return new CreditEntry(id, "backup-contention", "alice", "bob" + id.getLeastSignificantBits(), 10_000L, null, note);
    }

    private static final class BlockingManifestFileOps extends RecoveryFileOps {
        private final CountDownLatch artifactPhase = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();
        private final AtomicInteger manifestWrites = new AtomicInteger();

        @Override
        void writeString(Path target, String value, Charset charset) throws IOException {
            if (target.getFileName().toString().equals("manifest.json.tmp")) {
                manifestWrites.incrementAndGet();
                if (blocked.compareAndSet(false, true)) {
                    artifactPhase.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("timed out waiting to release manifest write");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("manifest write interrupted", exception);
                    }
                }
            }
            super.writeString(target, value, charset);
        }
    }
}
