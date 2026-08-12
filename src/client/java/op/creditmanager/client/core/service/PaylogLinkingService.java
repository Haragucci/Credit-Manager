package op.creditmanager.client.core.service;

import op.creditmanager.client.config.PaylogAutoLinkMode;
import op.creditmanager.client.core.CreditManagerCore.CreditException;
import op.creditmanager.client.core.CreditManagerCore.PaylogLinkResult;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;

import java.util.List;
import java.util.UUID;

public final class PaylogLinkingService {
    private final CreditOperations operations;

    public PaylogLinkingService(CreditOperations operations) {
        this.operations = operations;
    }

    public PaylogLinkResult addPaylogPayment(UUID dealId, UUID paylogId, long requestedAmountMinor, long timestamp,
                                             String note) throws CreditException {
        return link(paylogId, dealId, false, false, requestedAmountMinor, timestamp, note, "PAYLOG_SELECTED");
    }

    public PaylogLinkResult linkPaylogToDeal(UUID paylogId, UUID dealId) throws CreditException {
        return link(paylogId, dealId, false, false, null, 0L, null, "PAYLOG_MANUAL");
    }

    public PaylogLinkResult autoLinkDetectedPaylog(UUID paylogId, PaylogAutoLinkMode mode,
                                                    boolean completeOverpay) throws CreditException {
        operations.requireWritable();
        TransactionEntry paylog = operations.getPaylog(paylogId);
        long availableMinor = paylog.getRemainingAmountMinor();
        if (availableMinor == 0L) return PaylogLinkResult.alreadyConsumed(paylog);
        List<CreditEntry> candidates = operations.matchingActiveDeals(paylog);
        if (candidates.isEmpty()) return PaylogLinkResult.noMatchingDeal(paylog);
        PaylogAutoLinkMode resolvedMode = mode == null ? PaylogAutoLinkMode.OFF : mode;
        if (resolvedMode == PaylogAutoLinkMode.OFF) return PaylogLinkResult.autoLinkDisabled(paylog, candidates);

        List<CreditEntry> exact = candidates.stream()
                .filter(candidate -> candidate.getRemainingAmountMinor() == availableMinor)
                .toList();
        if (!exact.isEmpty()) return decideTier(paylog, exact, false, availableMinor);
        if (resolvedMode == PaylogAutoLinkMode.EXACT_ONLY) return PaylogLinkResult.noSingleDealFits(paylog);

        List<CreditEntry> partial = candidates.stream()
                .filter(candidate -> candidate.getRemainingAmountMinor() > availableMinor)
                .toList();
        if (!partial.isEmpty()) return decideTier(paylog, partial, false, availableMinor);
        if (resolvedMode != PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE || !completeOverpay) {
            return PaylogLinkResult.noSingleDealFits(paylog);
        }

        List<CreditEntry> overpay = candidates.stream()
                .filter(candidate -> candidate.getRemainingAmountMinor() < availableMinor)
                .toList();
        if (overpay.isEmpty()) return PaylogLinkResult.noSingleDealFits(paylog);
        return decideTier(paylog, overpay, true, availableMinor);
    }

    public List<CreditEntry> getLinkableCreditsForPaylog(TransactionEntry paylog) {
        return paylog == null ? List.of() : operations.matchingActiveDeals(paylog);
    }

    private PaylogLinkResult decideTier(TransactionEntry paylog, List<CreditEntry> tier, boolean overpay,
                                        long availableMinor) throws CreditException {
        if (tier.size() != 1) return PaylogLinkResult.ambiguous(paylog, tier);
        CreditEntry selected = tier.getFirst();
        return link(paylog.getId(), selected.getId(), true, overpay, availableMinor, paylog.getTimestamp(), null,
                "PAYLOG_AUTO");
    }

    private PaylogLinkResult link(UUID paylogId, UUID dealId, boolean automatic, boolean allowAutomaticOverpay,
                                  Long requestedAmountMinor, long requestedTimestamp, String note,
                                  String source) throws CreditException {
        operations.requireWritable();
        TransactionEntry paylog = operations.getPaylog(paylogId);
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.validateActive(entry);
        if (!operations.samePlayer(paylog.getFromPlayer(), entry.getDebtor())
                || !operations.samePlayer(paylog.getToPlayer(), entry.getCreditor())) {
            throw new CreditException("Dieser Paylog passt nicht zu den Parteien des Deals.");
        }
        long availableMinor = paylog.getRemainingAmountMinor();
        if (availableMinor == 0L) return PaylogLinkResult.alreadyConsumed(paylog);
        CreditEntry draft = operations.copyCredit(entry);
        long remainingBeforeMinor = draft.getRemainingAmountMinor();
        if (automatic && !allowAutomaticOverpay && availableMinor > remainingBeforeMinor) {
            return PaylogLinkResult.noSingleDealFits(paylog);
        }
        long wantedMinor = requestedAmountMinor == null ? availableMinor : requestedAmountMinor;
        operations.validateAmountMinor(wantedMinor);
        long bookedMinor = Math.min(Math.min(wantedMinor, availableMinor), remainingBeforeMinor);
        Payment payment = new Payment(draft.getId(), draft.getDebtor(), draft.getCreditor(), bookedMinor, null,
                source == null ? automatic ? "PAYLOG_AUTO" : "PAYLOG_MANUAL" : source);
        payment.setPaylogId(paylog.getId());
        payment.setTimestamp(requestedTimestamp > 0 ? requestedTimestamp : paylog.getTimestamp());
        payment.setNote(operations.normalizeNote(note));
        draft.addPayment(payment);
        draft.setArchived(false);
        operations.commitMutation(draft, List.of(payment), List.of(),
                operations.paymentEvents(draft, payment, remainingBeforeMinor), entry);
        return PaylogLinkResult.linked(paylog, entry, payment, availableMinor - bookedMinor, automatic);
    }
}
