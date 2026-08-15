package op.creditmanager.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class wwwCreditManagerShutdownCoordinatorTest {
    private static final CreditManagerShutdownCoordinator.ShutdownTimeouts TIMEOUTS =
            new CreditManagerShutdownCoordinator.ShutdownTimeouts(Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO);

    @Test
    void drainsWritesBeforeBackupAndStopsReadersBeforeLeaseRelease() {
        RecordingServices services = new RecordingServices();

        CreditManagerShutdownCoordinator.ShutdownResult result =
                CreditManagerShutdownCoordinator.shutdown(services, TIMEOUTS);

        assertEquals(List.of("mutations", "mutations-idle", "recovery", "recovery-idle",
                "backup", "queries", "skins", "release"), services.calls);
        assertTrue(result.mutationsStopped());
        assertTrue(result.recoveryStopped());
        assertTrue(result.backupFlushed());
        assertTrue(result.queriesStopped());
        assertTrue(result.skinFlushed());
        assertTrue(result.leaseReleased());
    }

    @Test
    void mutationTimeoutPreventsBackupAndLeaseRelease() {
        RecordingServices services = new RecordingServices();
        services.mutationsStopped = false;

        CreditManagerShutdownCoordinator.ShutdownResult result =
                CreditManagerShutdownCoordinator.shutdown(services, TIMEOUTS);

        assertFalse(result.mutationsStopped());
        assertFalse(result.backupFlushed());
        assertFalse(result.leaseReleased());
        assertFalse(services.calls.contains("backup"));
        assertFalse(services.calls.contains("release"));
        assertTrue(services.calls.contains("queries"));
        assertTrue(services.calls.contains("skins"));
    }

    @Test
    void workerTimeoutAfterBackupRetainsStorageLease() {
        RecordingServices services = new RecordingServices();
        services.queriesStopped = false;

        CreditManagerShutdownCoordinator.ShutdownResult result =
                CreditManagerShutdownCoordinator.shutdown(services, TIMEOUTS);

        assertTrue(result.backupFlushed());
        assertFalse(result.queriesStopped());
        assertFalse(result.leaseReleased());
        assertFalse(services.calls.contains("release"));
    }

    private static final class RecordingServices implements CreditManagerShutdownCoordinator.ShutdownServices {
        private final List<String> calls = new ArrayList<>();
        private boolean mutationsStopped = true;
        private boolean mutationsIdle = true;
        private boolean recoveryStopped = true;
        private boolean recoveryIdle = true;
        private boolean backupFlushed = true;
        private boolean queriesStopped = true;
        private boolean skinFlushed = true;

        @Override
        public boolean stopMutations(Duration timeout) {
            calls.add("mutations");
            return mutationsStopped;
        }

        @Override
        public boolean mutationsIdle() {
            calls.add("mutations-idle");
            return mutationsIdle;
        }

        @Override
        public boolean stopRecovery(Duration timeout) {
            calls.add("recovery");
            return recoveryStopped;
        }

        @Override
        public boolean recoveryIdle() {
            calls.add("recovery-idle");
            return recoveryIdle;
        }

        @Override
        public boolean flushBackup(Duration timeout) {
            calls.add("backup");
            return backupFlushed;
        }

        @Override
        public boolean stopQueries(Duration timeout) {
            calls.add("queries");
            return queriesStopped;
        }

        @Override
        public boolean stopSkins(Duration timeout) {
            calls.add("skins");
            return skinFlushed;
        }

        @Override
        public boolean releaseStorage() {
            calls.add("release");
            return true;
        }
    }
}
