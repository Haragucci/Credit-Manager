package op.creditmanager.client.core.service;

import op.creditmanager.client.core.CreditManagerCore;
import op.creditmanager.client.core.CreditManagerCore.CreditException;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PaymentApplicationService {
    private final CreditRepository repository;
    private final CreditOperations operations;
    private final CreditEventFactory events;

    public PaymentApplicationService(CreditRepository repository, CreditOperations operations, CreditEventFactory events) {
        this.repository = repository;
        this.operations = operations;
        this.events = events;
    }

    public Payment addMoneyPayment(UUID dealId, double amount) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.requireWritable();
        operations.validateActive(entry);
        operations.validateAmount(amount);
        CreditEntry draft = operations.copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        Payment payment = new Payment(dealId, entry.getDebtor(), entry.getCreditor(), Math.min(amount, remainingBefore), null, "MANUELL");
        draft.addPayment(payment);
        operations.commitMutation(draft, List.of(payment), List.of(), operations.paymentEvents(draft, payment, remainingBefore), entry);
        return payment;
    }

    public Payment addMoneyPayment(UUID dealId, String fromPlayer, double amount) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.validatePaymentSource(fromPlayer, entry);
        return addMoneyPayment(dealId, amount);
    }

    public Payment addItemPayment(UUID dealId, List<String> items, double value, String nbt) throws CreditException {
        return addItemPayment(dealId, items, value, nbt == null || nbt.isBlank() ? List.of() : List.of(nbt));
    }

    public Payment addItemPayment(UUID dealId, List<String> items, double value, List<String> nbtEntries) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.requireWritable();
        operations.validateActive(entry);
        operations.validateAmount(value);
        if (items == null || items.isEmpty()) throw new CreditException("Mindestens ein Item muss ausgewählt werden.");
        CreditEntry draft = operations.copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        Payment payment = new Payment(dealId, entry.getDebtor(), entry.getCreditor(), Math.min(value, remainingBefore), new ArrayList<>(items), "MANUELL");
        payment.setItemNbtEntries(nbtEntries);
        payment.setItemNbt(nbtEntries == null || nbtEntries.isEmpty() ? null : nbtEntries.get(0));
        draft.addPayment(payment);
        operations.commitMutation(draft, List.of(payment), List.of(), operations.paymentEvents(draft, payment, remainingBefore), entry);
        return payment;
    }

    public Payment addItemPayment(UUID dealId, String fromPlayer, List<String> items, double value, String nbt) throws CreditException {
        return addItemPayment(dealId, fromPlayer, items, value, nbt == null || nbt.isBlank() ? List.of() : List.of(nbt));
    }

    public Payment addItemPayment(UUID dealId, String fromPlayer, List<String> items, double value, List<String> nbtEntries) throws CreditException {
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.validatePaymentSource(fromPlayer, entry);
        return addItemPayment(dealId, items, value, nbtEntries);
    }

    public void deletePayment(UUID paymentId) throws CreditException {
        Payment payment = repository.getAllPayments().stream().filter(value -> value.getId().equals(paymentId)).findFirst()
                .orElseThrow(() -> new CreditException("Zahlung nicht gefunden."));
        CreditEntry entry = operations.getSafeCredit(payment.getCreditId());
        operations.requireWritable();
        CreditEntry draft = operations.copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.removePayment(paymentId);
        if (CreditManagerCore.STATUS_OPEN.equals(draft.getStatus()) || CreditManagerCore.STATUS_PARTIAL.equals(draft.getStatus())) {
            draft.setArchived(false);
            draft.setCompletedAt(null);
        }
        CreditEventEntry event = events.create(CreditEventType.PAYMENT_DELETED, draft, payment.getAmount() == null ? 0D : payment.getAmount(),
                remainingBefore, "Zahlung gelöscht", payment.getFromPlayer(), payment.getSource(), !payment.getItems().isEmpty());
        operations.commitMutation(draft, List.of(), List.of(paymentId), List.of(event), entry);
    }
}
