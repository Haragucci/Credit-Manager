package op.creditmanager.client.core.service;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditEventRepository;
import op.creditmanager.client.core.CreditManagerCore.CreditException;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.util.List;
import java.util.UUID;

public final class CreditMutationService {
    private final CreditRepository repository;
    private final CreditSnapshotMapper snapshots;

    public CreditMutationService(CreditRepository repository, CreditSnapshotMapper snapshots) {
        this.repository = repository;
        this.snapshots = snapshots;
    }

    public void commit(CreditEntry draft, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                       List<CreditEventEntry> events, CreditEntry published) throws CreditException {
        DatabaseManager.CreditMutation mutation = new DatabaseManager.CreditMutation(draft, paymentUpserts, paymentDeletions, events);
        if (!DatabaseManager.getInstance().commitCreditMutation(mutation)) {
            throw new CreditException("Vorgang wurde nicht gespeichert; der vorherige Datenstand bleibt unverändert.");
        }
        if (!repository.load()) {
            repository.applyCommittedMutation(draft, paymentUpserts, paymentDeletions, events);
            CreditEventRepository.getInstance().bind(repository);
            CreditEventRepository.getInstance().acceptCommittedEvents(events);
            TransactionRepository.getInstance().load();
            snapshots.synchronize(published == null ? draft : published, draft);
            CreditManagerClient.LOGGER.warn("CreditManager commit succeeded, but the follow-up reload failed. The committed deal remains visible from the staged in-memory state.");
            return;
        }
        CreditEventRepository.getInstance().bind(repository);
        CreditEventRepository.getInstance().load();
        TransactionRepository.getInstance().load();
        CreditEntry persisted = repository.findCreditById(draft.getId())
                .orElseThrow(() -> new CreditException("Gespeicherter Deal konnte nicht erneut geladen werden."));
        CreditEntry target = published == null ? draft : published;
        snapshots.synchronize(target, persisted);
        repository.replaceLoadedCredit(target);
    }
}
