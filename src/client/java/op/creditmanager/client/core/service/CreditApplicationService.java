package op.creditmanager.client.core.service;

import op.creditmanager.client.core.CreditManagerCore;
import op.creditmanager.client.core.CreditManagerCore.CreditException;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventType;

import java.util.List;
import java.util.UUID;

public final class CreditApplicationService {
    private final CreditRepository repository;
    private final CreditOperations operations;

    public CreditApplicationService(CreditRepository repository, CreditOperations operations) {
        this.repository = repository;
        this.operations = operations;
    }

    public CreditEntry createCredit(String creditor, String debtor, double amount, Long dueDate, String label, String note) throws CreditException {
        operations.requireWritable();
        operations.validateNames(creditor, debtor);
        operations.validateAmount(amount);
        operations.validateDealInput(label, note, dueDate);
        String dealName = CreditEntry.buildDealName(debtor, creditor, label);
        boolean exists = repository.getAllCredits().stream().anyMatch(entry -> dealName.equalsIgnoreCase(entry.getDealName())
                && !CreditManagerCore.STATUS_CANCELLED.equals(entry.getStatus()));
        if (exists) throw new CreditException("Ein aktiver Deal mit diesem Namen existiert bereits.");
        CreditEntry entry = new CreditEntry(UUID.randomUUID(), dealName, operations.lower(creditor), operations.lower(debtor), amount, dueDate, note);
        operations.commitMutation(entry, List.of(), List.of(), List.of(operations.event(CreditEventType.CREDIT_CREATED, entry,
                entry.getAmount(), entry.getAmount(), entry.getNote(), creditor, "CREATE", false)), null);
        return entry;
    }

    public CreditEntry archiveCredit(UUID dealId) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.requireWritable();
        if (entry.isArchived()) return entry;
        CreditEntry draft = operations.copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.setArchived(true);
        operations.commitMutation(draft, List.of(), List.of(), List.of(operations.event(CreditEventType.CREDIT_ARCHIVED, draft, draft.getAmount(), remainingBefore,
                "Deal archiviert", null, "ARCHIVE", false)), entry);
        return entry;
    }

    public CreditEntry closeCredit(UUID dealId) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.requireWritable();
        operations.validateActive(entry);
        CreditEntry draft = operations.copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.setStatus(CreditManagerCore.STATUS_CLOSED);
        draft.setCompletedAt(System.currentTimeMillis());
        operations.commitMutation(draft, List.of(), List.of(), List.of(operations.event(CreditEventType.CREDIT_CLOSED, draft, draft.getAmount(), remainingBefore,
                "Deal manuell abgeschlossen", null, "CLOSE", false)), entry);
        return entry;
    }

    public CreditEntry reactivateCredit(UUID dealId) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.requireWritable();
        if (!entry.isArchived() && !CreditManagerCore.STATUS_CANCELLED.equals(entry.getStatus()) && !CreditManagerCore.STATUS_CLOSED.equals(entry.getStatus())) return entry;
        CreditEntry draft = operations.copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.setArchived(false);
        draft.setCompletedAt(null);
        draft.setStatus(CreditManagerCore.STATUS_OPEN);
        draft.refreshPaymentState();
        operations.commitMutation(draft, List.of(), List.of(), List.of(operations.event(CreditEventType.CREDIT_REACTIVATED, draft, draft.getAmount(), remainingBefore,
                "Deal reaktiviert", null, "REACTIVATE", false)), entry);
        return entry;
    }

    public CreditEntry updateCredit(UUID dealId, String creditor, String debtor, double amount, Long dueDate, String label, String note) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.requireWritable();
        operations.validateNames(creditor, debtor);
        operations.validateAmount(amount);
        operations.validateDealInput(label, note, dueDate);
        if (amount + 0.0001D < entry.getPaidAmount()) throw new CreditException("Der Gesamtbetrag darf nicht kleiner als bereits bezahlt sein.");
        boolean counterpartyChanged = !operations.lower(creditor).equals(entry.getCreditor()) || !operations.lower(debtor).equals(entry.getDebtor());
        if (counterpartyChanged && !entry.getPayments().isEmpty()) throw new CreditException("Die Gegenpartei kann nach vorhandenen Zahlungen nicht geändert werden.");
        String name = CreditEntry.buildDealName(debtor, creditor, label);
        boolean exists = repository.getAllCredits().stream().anyMatch(other -> !other.getId().equals(dealId)
                && name.equalsIgnoreCase(other.getDealName()) && !CreditManagerCore.STATUS_CANCELLED.equals(other.getStatus()));
        if (exists) throw new CreditException("Ein aktiver Deal mit diesem Namen existiert bereits.");
        CreditEntry draft = operations.copyCredit(entry);
        double previousAmount = draft.getAmount();
        draft.setCreditor(operations.lower(creditor));
        draft.setDebtor(operations.lower(debtor));
        draft.setDealName(name);
        draft.setAmount(amount);
        draft.setDueDate(dueDate);
        draft.setNote(note == null || note.isBlank() ? null : note.trim());
        draft.refreshPaymentState();
        operations.commitMutation(draft, List.of(), List.of(), List.of(operations.event(CreditEventType.CREDIT_UPDATED, draft, amount, previousAmount,
                "Deal bearbeitet", null, "EDIT", false)), entry);
        return entry;
    }
}
