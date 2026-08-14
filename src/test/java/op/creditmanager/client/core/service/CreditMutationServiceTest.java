package op.creditmanager.client.core.service;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.storage.db.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditMutationServiceTest {

    @Test
    void preCommitFailureReturnsNotCommittedWithoutPublication() {
        RecordingRuntime runtime = new RecordingRuntime();
        CreditMutationService service = new CreditMutationService(
                mutation -> DatabaseManager.MutationCommitReceipt.notCommitted(), runtime);

        MutationCommitResult result = service.commit(credit(), List.of(), List.of(), List.of(), null);

        assertEquals(MutationCommitResult.Status.NOT_COMMITTED, result.status());
        assertFalse(result.committed());
        assertEquals(0, runtime.publishCount);
        assertEquals(0, runtime.reloadCount);
    }

    @Test
    void committedReceiptPublishesWithTheCommittedRevisionWithoutAnotherDatabaseRead() {
        RecordingRuntime runtime = new RecordingRuntime();
        AtomicInteger commits = new AtomicInteger();
        CreditMutationService service = new CreditMutationService(mutation -> {
            commits.incrementAndGet();
            return DatabaseManager.MutationCommitReceipt.committed(42L);
        }, runtime);

        MutationCommitResult result = service.commit(credit(), List.of(), List.of(), List.of(), null);

        assertEquals(MutationCommitResult.Status.COMMITTED_SYNCED, result.status());
        assertEquals(42L, result.committedRevision());
        assertEquals(42L, runtime.publishedRevision);
        assertEquals(1, commits.get());
        assertEquals(1, runtime.publishCount);
        assertEquals(0, runtime.reloadCount);
    }

    @Test
    void publicationFailureReloadsAuthoritativeStateAndRemainsCommitted() {
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.publicationFailure = true;
        runtime.reloadResult = true;
        CreditMutationService service = new CreditMutationService(
                mutation -> DatabaseManager.MutationCommitReceipt.committed(7L), runtime);

        MutationCommitResult result = service.commit(credit(), List.of(), List.of(), List.of(), null);

        assertEquals(MutationCommitResult.Status.COMMITTED_RELOAD_REQUIRED, result.status());
        assertTrue(result.committed());
        assertEquals(1, runtime.publishCount);
        assertEquals(1, runtime.reloadCount);
        assertEquals(0, runtime.degradeCount);
    }

    @Test
    void publicationAndReloadFailureEnterDegradedModeWithoutReportingNotCommitted() {
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.publicationFailure = true;
        runtime.reloadResult = false;
        CreditMutationService service = new CreditMutationService(
                mutation -> DatabaseManager.MutationCommitReceipt.committed(8L), runtime);

        MutationCommitResult result = service.commit(credit(), List.of(), List.of(), List.of(), null);

        assertEquals(MutationCommitResult.Status.COMMITTED_DEGRADED, result.status());
        assertTrue(result.committed());
        assertEquals(1, runtime.reloadCount);
        assertEquals(1, runtime.degradeCount);
        assertTrue(result.userMessage().startsWith("Vorgang wurde gespeichert"));
    }


    @Test
    void committedButUnverifiedReceiptReloadsAndReconcilesPublishedReferenceWithoutDegrading() {
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.reloadResult = true;
        CreditEntry draft = credit();
        draft.setStatus("PARTIAL");
        draft.setPaidAmountMinor(1_000L);
        CreditEntry published = credit();
        published.setId(draft.getId());
        CreditMutationService service = new CreditMutationService(
                mutation -> DatabaseManager.MutationCommitReceipt.committedUnverified(9L), runtime);

        MutationCommitResult result = service.commit(draft, List.of(), List.of(), List.of(), published);

        assertEquals(MutationCommitResult.Status.COMMITTED_RELOAD_REQUIRED, result.status());
        assertTrue(result.committed());
        assertEquals(0, runtime.publishCount);
        assertEquals(1, runtime.reloadCount);
        assertEquals(1, runtime.synchronizeAfterReloadCount);
        assertEquals(0, runtime.degradeCount);
    }

    @Test
    void committedButUnverifiedReceiptNeverReportsNotCommittedOrPublishesOptimistically() {
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.reloadResult = false;
        CreditMutationService service = new CreditMutationService(
                mutation -> DatabaseManager.MutationCommitReceipt.committedUnverified(9L), runtime);

        MutationCommitResult result = service.commit(credit(), List.of(), List.of(), List.of(), null);

        assertEquals(MutationCommitResult.Status.COMMITTED_DEGRADED, result.status());
        assertTrue(result.committed());
        assertEquals(0, runtime.publishCount);
        assertEquals(1, runtime.reloadCount);
        assertEquals(1, runtime.degradeCount);
    }

    private CreditEntry credit() {
        return new CreditEntry(UUID.randomUUID(), "deal", "creditor", "debtor", 10_000L, null, null);
    }

    private static final class RecordingRuntime implements CreditMutationService.MutationRuntime {
        private int publishCount;
        private int reloadCount;
        private int degradeCount;
        private int synchronizeAfterReloadCount;
        private long publishedRevision = -1L;
        private boolean publicationFailure;
        private boolean reloadResult;

        @Override
        public void publish(CreditEntry draft, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                            List<CreditEventEntry> events, CreditEntry published, long committedRevision) {
            publishCount++;
            publishedRevision = committedRevision;
            if (publicationFailure) throw new IllegalStateException("injected publication failure");
        }

        @Override
        public boolean reload() {
            reloadCount++;
            return reloadResult;
        }

        @Override
        public void synchronizeAfterReload(CreditEntry draft, CreditEntry published) {
            synchronizeAfterReloadCount++;
        }

        @Override
        public void degrade(String reason) {
            degradeCount++;
        }
    }
}
