package op.creditmanager.client.core.service;

import op.creditmanager.client.core.CreditManagerCore;
import op.creditmanager.client.core.CreditManagerCore.CreditException;
import op.creditmanager.client.core.CreditManagerCore.PaylogLinkResult;
import op.creditmanager.client.config.PaylogAutoLinkMode;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;

import java.util.List;
import java.util.UUID;

public final class PaylogLinkingService {
    private static final double EPSILON = 0.0001D;
    private final CreditOperations operations;

    public PaylogLinkingService(CreditOperations operations) {
        this.operations = operations;
    }

    public PaylogLinkResult addPaylogPayment(UUID dealId, UUID paylogId, double requestedAmount, long timestamp, String note) throws CreditException {
        return link(paylogId, dealId, false, false, requestedAmount, timestamp, note, "PAYLOG_SELECTED");
    }

    public PaylogLinkResult linkPaylogToDeal(UUID paylogId, UUID dealId) throws CreditException {
        return link(paylogId, dealId, false, false, Double.NaN, 0L, null, "PAYLOG_MANUAL");
    }

    public PaylogLinkResult autoLinkDetectedPaylog(UUID paylogId, PaylogAutoLinkMode mode,
                                                    boolean completeOverpay) throws CreditException {
        operations.requireWritable();
        TransactionEntry paylog = operations.getPaylog(paylogId);
        if (paylog.getRemainingAmount() <= EPSILON) return PaylogLinkResult.alreadyConsumed(paylog);
        List<CreditEntry> candidates = operations.matchingActiveDeals(paylog);
        if (candidates.isEmpty()) return PaylogLinkResult.noMatchingDeal(paylog);
        PaylogAutoLinkMode resolvedMode = mode == null ? PaylogAutoLinkMode.OFF : mode;
        if (resolvedMode == PaylogAutoLinkMode.OFF) return PaylogLinkResult.autoLinkDisabled(paylog, candidates.getFirst());

        double available = paylog.getRemainingAmount();
        CreditEntry exact = candidates.stream()
                .filter(candidate -> Math.abs(candidate.getRemainingAmount() - available) <= EPSILON)
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return link(paylogId, exact.getId(), true, false, available, paylog.getTimestamp(), null, "PAYLOG_AUTO");
        }
        if (resolvedMode == PaylogAutoLinkMode.EXACT_ONLY) return PaylogLinkResult.noSingleDealFits(paylog);

        CreditEntry partial = candidates.stream()
                .filter(candidate -> candidate.getRemainingAmount() + EPSILON >= available)
                .findFirst()
                .orElse(null);
        if (partial != null) {
            return link(paylogId, partial.getId(), true, false, available, paylog.getTimestamp(), null, "PAYLOG_AUTO");
        }
        if (resolvedMode != PaylogAutoLinkMode.EXACT_PARTIAL_OR_OVERPAY_CLOSE || !completeOverpay) {
            return PaylogLinkResult.noSingleDealFits(paylog);
        }
        CreditEntry overpay = candidates.getFirst();
        return link(paylogId, overpay.getId(), true, true, available, paylog.getTimestamp(), null, "PAYLOG_AUTO");
    }

    public List<CreditEntry> getLinkableCreditsForPaylog(TransactionEntry paylog) {
        return paylog == null ? List.of() : operations.matchingActiveDeals(paylog);
    }

    private PaylogLinkResult link(UUID paylogId, UUID dealId, boolean automatic, boolean allowAutomaticOverpay, double requestedAmount,
                                  long requestedTimestamp, String note, String source) throws CreditException {
        operations.requireWritable();
        TransactionEntry paylog = operations.getPaylog(paylogId);
        CreditEntry entry = operations.getSafeCredit(dealId);
        operations.validateActive(entry);
        if (!operations.samePlayer(paylog.getFromPlayer(), entry.getDebtor()) || !operations.samePlayer(paylog.getToPlayer(), entry.getCreditor())) {
            throw new CreditException("Dieser Paylog passt nicht zu den Parteien des Deals.");
        }
        double available = paylog.getRemainingAmount();
        if (available <= EPSILON) return PaylogLinkResult.alreadyConsumed(paylog);
        CreditEntry draft = operations.copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        if (automatic && !allowAutomaticOverpay && available > remainingBefore + EPSILON) return PaylogLinkResult.noSingleDealFits(paylog);
        double wanted = Double.isFinite(requestedAmount) ? requestedAmount : available;
        operations.validateAmount(wanted);
        double booked = Math.min(Math.min(wanted, available), remainingBefore);
        Payment payment = new Payment(draft.getId(), draft.getDebtor(), draft.getCreditor(), booked, null,
                source == null ? automatic ? "PAYLOG_AUTO" : "PAYLOG_MANUAL" : source);
        payment.setPaylogId(paylog.getId());
        payment.setTimestamp(requestedTimestamp > 0 ? requestedTimestamp : paylog.getTimestamp());
        payment.setNote(operations.normalizeNote(note));
        draft.addPayment(payment);
        draft.setArchived(false);
        operations.commitMutation(draft, List.of(payment), List.of(), operations.paymentEvents(draft, payment, remainingBefore), entry);
        return PaylogLinkResult.linked(paylog, entry, payment, available - booked, automatic);
    }
}
