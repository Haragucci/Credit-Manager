package op.creditmanager.client.core.service;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;

import java.util.ArrayList;

public final class CreditSnapshotMapper {
    public CreditEntry copyCredit(CreditEntry source) {
        CreditEntry copy = new CreditEntry();
        copy.setId(source.getId());
        copy.setDealName(source.getDealName());
        copy.setCreditor(source.getCreditor());
        copy.setDebtor(source.getDebtor());
        copy.setAmountMinor(source.getAmountMinor());
        copy.setPaidAmountMinor(source.getPaidAmountMinor());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setDueDate(source.getDueDate());
        copy.setStatus(source.getStatus());
        copy.setNote(source.getNote());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setArchived(source.isArchived());
        copy.setPayments(new ArrayList<>(source.getPayments().stream().map(this::copyPayment).toList()));
        return copy;
    }

    public Payment copyPayment(Payment source) {
        Payment copy = new Payment(source.getCreditId(), source.getFromPlayer(), source.getToPlayer(), source.getAmountMinor(),
                new ArrayList<>(source.getItems()), source.getSource());
        copy.setId(source.getId());
        copy.setItemNbt(source.getItemNbt());
        copy.setItemNbtEntries(source.getItemNbtEntries());
        copy.setTimestamp(source.getTimestamp());
        copy.setPaylogId(source.getPaylogId());
        copy.setNote(source.getNote());
        copy.setPaymentKind(source.getPaymentKind());
        return copy;
    }

    public void synchronize(CreditEntry target, CreditEntry source) {
        target.setId(source.getId());
        target.setDealName(source.getDealName());
        target.setCreditor(source.getCreditor());
        target.setDebtor(source.getDebtor());
        target.setAmountMinor(source.getAmountMinor());
        target.setPaidAmountMinor(source.getPaidAmountMinor());
        target.setCreatedAt(source.getCreatedAt());
        target.setDueDate(source.getDueDate());
        target.setStatus(source.getStatus());
        target.setNote(source.getNote());
        target.setCompletedAt(source.getCompletedAt());
        target.setArchived(source.isArchived());
        target.setPayments(new ArrayList<>(source.getPayments().stream().map(this::copyPayment).toList()));
    }
}
