package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.MultiProcessStorageLeaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardKillDatabaseDurabilityTest {
    @TempDir Path temporary;

    @Test
    void confirmedCommitSurvivesForcibleJvmTerminationExactly() throws Exception {
        UUID creditId = UUID.randomUUID();
        Process child = MultiProcessStorageLeaseIntegrationTest.start(DatabaseCrashChildMain.class,
                temporary.toString(), "-", creditId.toString());
        try {
            MultiProcessStorageLeaseIntegrationTest.awaitLine(child, "COMMIT_CONFIRMED", Duration.ofSeconds(15));
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            try (StorageTestScope scope = new StorageTestScope()) {
                scope.configurePrimary(temporary, null);
                DatabaseCoordinator coordinator = new DatabaseCoordinator();
                coordinator.initialize();
                DatabaseManager.DatabaseState state = coordinator.loadRuntimeCreditState();
                assertEquals(1, state.credits().size());
                assertEquals(creditId, state.credits().getFirst().getId());
                assertEquals(77_777L, state.credits().getFirst().getAmountMinor());
                assertEquals("durable", state.credits().getFirst().getNote());
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
