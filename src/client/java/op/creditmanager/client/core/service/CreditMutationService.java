package op.creditmanager.client.core.service;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditEventRepository;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.util.List;
import java.util.UUID;

public final class CreditMutationService {
    private final MutationCommitter committer;
    private final MutationRuntime runtime;

    public CreditMutationService(CreditRepository repository, CreditSnapshotMapper snapshots) {
        this(DatabaseManager.getInstance()::commitCreditMutationWithReceipt,
                new RepositoryMutationRuntime(repository, snapshots));
    }

    CreditMutationService(MutationCommitter committer, MutationRuntime runtime) {
        this.committer = committer;
        this.runtime = runtime;
    }

    public MutationCommitResult commit(CreditEntry draft, List<Payment> paymentUpserts,
                                       List<UUID> paymentDeletions, List<CreditEventEntry> events,
                                       CreditEntry published) {
        DatabaseManager.CreditMutation mutation = new DatabaseManager.CreditMutation(draft, paymentUpserts,
                paymentDeletions, events);
        DatabaseManager.MutationCommitReceipt receipt = committer.commit(mutation);
        if (receipt == null || !receipt.committed()) return MutationCommitResult.notCommitted();

        RuntimeException synchronizationFailure = null;
        if (receipt.verified()) {
            try {
                runtime.publish(draft, paymentUpserts, paymentDeletions, events, published, receipt.revision());
                return new MutationCommitResult(MutationCommitResult.Status.COMMITTED_SYNCED, receipt.revision());
            } catch (RuntimeException publicationFailure) {
                synchronizationFailure = publicationFailure;
                CreditManagerClient.LOGGER.error("Committed CreditManager mutation could not be published incrementally", publicationFailure);
            }
        } else {
            synchronizationFailure = new IllegalStateException("Committed CreditManager mutation was not confirmed by the verification read");
            CreditManagerClient.LOGGER.error("Committed CreditManager mutation requires an authoritative runtime reload");
        }
        try {
            if (runtime.reload()) {
                runtime.synchronizeAfterReload(draft, published);
                return new MutationCommitResult(MutationCommitResult.Status.COMMITTED_RELOAD_REQUIRED,
                        receipt.revision());
            }
        } catch (RuntimeException reloadFailure) {
            synchronizationFailure.addSuppressed(reloadFailure);
            CreditManagerClient.LOGGER.error("Committed CreditManager mutation could not be reloaded", reloadFailure);
        }
        try {
            runtime.degrade("Eine persistierte Änderung konnte nicht mit dem Laufzeitstatus synchronisiert werden.");
        } catch (RuntimeException degradeFailure) {
            synchronizationFailure.addSuppressed(degradeFailure);
            CreditManagerClient.LOGGER.error("CreditManager write gate could not enter degraded mode", degradeFailure);
        }
        return new MutationCommitResult(MutationCommitResult.Status.COMMITTED_DEGRADED, receipt.revision());
    }

    @FunctionalInterface
    interface MutationCommitter {
        DatabaseManager.MutationCommitReceipt commit(DatabaseManager.CreditMutation mutation);
    }

    interface MutationRuntime {
        void publish(CreditEntry draft, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                     List<CreditEventEntry> events, CreditEntry published, long committedRevision);
        boolean reload();
        default void synchronizeAfterReload(CreditEntry draft, CreditEntry published) { }
        void degrade(String reason);
    }

    private static final class RepositoryMutationRuntime implements MutationRuntime {
        private final CreditRepository repository;
        private final CreditSnapshotMapper snapshots;

        private RepositoryMutationRuntime(CreditRepository repository, CreditSnapshotMapper snapshots) {
            this.repository = repository;
            this.snapshots = snapshots;
        }

        @Override
        public void publish(CreditEntry draft, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                            List<CreditEventEntry> events, CreditEntry published, long committedRevision) {
            repository.applyCommittedMutation(draft, paymentUpserts, paymentDeletions, events, committedRevision);
            CreditEventRepository.getInstance().acceptCommittedEvents(events, committedRevision);
            TransactionRepository.getInstance().acceptCommittedMutation(committedRevision);
            CreditEntry target = published == null ? draft : published;
            snapshots.synchronize(target, draft);
            repository.replaceLoadedCredit(target);
        }

        @Override
        public boolean reload() {
            if (!repository.load()) {
                return false;
            }

            CreditEventRepository eventRepository = CreditEventRepository.getInstance();
            if (!eventRepository.load()) {
                return false;
            }

            TransactionRepository.getInstance().load();

            long authoritativeRevision = DatabaseManager.getInstance().revision();

            return repository.getRevision() == authoritativeRevision
                    && eventRepository.getRevision() == authoritativeRevision;
        }

        @Override
        public void synchronizeAfterReload(CreditEntry draft, CreditEntry published) {
            if (draft == null || draft.getId() == null) {
                return;
            }

            CreditEntry authoritative = repository.findCreditById(draft.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Committed credit is missing after authoritative reload"
                    ));

            CreditEntry target = published != null ? published : draft;

            snapshots.synchronize(target, authoritative);

            repository.replaceLoadedCredit(target);
        }

        @Override
        public void degrade(String reason) {
            DatabaseManager.getInstance().markRuntimeStateDegraded(reason);
        }
    }
}
