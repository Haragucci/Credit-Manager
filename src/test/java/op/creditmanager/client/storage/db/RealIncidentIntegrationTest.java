package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.ProcessStorageLease;
import op.creditmanager.client.storage.StorageRootResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RealIncidentIntegrationTest {
    @TempDir Path temporary;

    @Test
    void legacyOverlaySecondInstanceDeletionDebtPaymentRestartAndBackupRemainSafe() throws Exception {
        Path instance = temporary.resolve("LabyMod").resolve("instances").resolve("incident-instance");
        Path overlay = instance.resolve("overlay");
        Path legacy = overlay.resolve("CreditManagerLogs");
        Path canonical = instance.resolve("CreditManagerLogs");
        UUID debtId = UUID.randomUUID();
        long amountMinor = 212_121L;
        long partialPaymentMinor = 2_123L;
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(legacy);
            DatabaseCoordinator oldLayout = new DatabaseCoordinator();
            oldLayout.initialize();
            CreditEntry legacyDebt = new CreditEntry(debtId, "bob-alice-runtime-incident", "alice", "bob",
                    amountMinor, null, "legacy overlay debt");
            assertTrue(oldLayout.commitCreditMutation(new DatabaseManager.CreditMutation(
                    legacyDebt, List.of(), List.of(), List.of())));

            scope.configure(canonical, labyLocation(canonical, legacy), FileManager.StorageAccessState.EXTERNALLY_MANAGED);
            DatabaseCoordinator primaryDatabase = new DatabaseCoordinator();
            primaryDatabase.initialize();
            assertTrue(primaryDatabase.isHealthy());
            assertEquals(debtId, primaryDatabase.loadCreditState().credits().getFirst().getId());

            ProcessStorageLease primaryLease = ProcessStorageLease.tryAcquire(canonical).orElseThrow();
            try {
                assertTrue(ProcessStorageLease.tryAcquire(canonical).isEmpty());
                deleteTree(overlay);
                assertFalse(Files.exists(legacy));
                assertTrue(Files.isRegularFile(canonical.resolve("creditmanager.mv.db")));
                CreditEntry debt = primaryDatabase.loadCreditState().credits().getFirst();
                Payment payment = new Payment(debtId, "bob", "alice", partialPaymentMinor, List.of(), "MANUAL");
                debt.addPayment(payment);
                DatabaseManager.MutationCommitReceipt receipt = primaryDatabase.commitCreditMutationWithReceipt(
                        new DatabaseManager.CreditMutation(debt, List.of(payment), List.of(), List.of()));
                assertTrue(receipt.committed());
                assertEquals("PARTIAL", debt.getStatus());
                assertEquals(partialPaymentMinor, debt.getPaidAmountMinor());
                assertTrue(primaryDatabase.createBackup());
                DatabaseManager.BackupManifestEntry protectedBackup = primaryDatabase.listBackups().stream()
                        .filter(DatabaseManager.BackupManifestEntry::automaticRestoreEligible)
                        .max(Comparator.comparingLong(DatabaseManager.BackupManifestEntry::createdAt))
                        .orElseThrow();
                assertTrue(protectedBackup.revision() >= receipt.revision());
                assertEquals(1, protectedBackup.creditCount());
                assertEquals(1, protectedBackup.paymentCount());
            } finally {
                primaryLease.close();
            }

            try (ProcessStorageLease restartedLease = ProcessStorageLease.tryAcquire(canonical).orElseThrow()) {
                DatabaseCoordinator restarted = new DatabaseCoordinator();
                restarted.initialize();
                DatabaseManager.DatabaseState state = restarted.loadCreditState();
                assertTrue(restarted.isHealthy());
                assertEquals(1, state.credits().size());
                assertEquals(1, state.payments().size());
                CreditEntry persisted = state.credits().getFirst();
                assertEquals(debtId, persisted.getId());
                assertEquals(amountMinor, persisted.getAmountMinor());
                assertEquals(partialPaymentMinor, persisted.getPaidAmountMinor());
                assertEquals("PARTIAL", persisted.getStatus());
                assertEquals(partialPaymentMinor, state.payments().getFirst().getAmountMinor());
            }
        }
    }

    private StorageRootResolver.StorageLocation labyLocation(Path canonical, Path legacy) {
        return new StorageRootResolver.StorageLocation(canonical.toAbsolutePath().normalize(), legacy.toAbsolutePath().normalize(),
                StorageRootResolver.StorageEnvironment.LABYMOD, "incident-instance",
                StorageRootResolver.ResolutionSource.CROSS_VALIDATED, true, List.of("test"));
    }

    private void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
