package op.creditmanager.client.core.service;

import op.creditmanager.client.core.CreditManagerCore.CreditException;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;

import java.util.List;
import java.util.UUID;

public interface CreditOperations {
    CreditEntry getSafeCredit(UUID id) throws CreditException;
    void requireWritable() throws CreditException;
    void validateActive(CreditEntry entry) throws CreditException;
    void validateAmount(double amount) throws CreditException;
    void validateNames(String creditor, String debtor) throws CreditException;
    void validateDealInput(String label, String note, Long dueDate) throws CreditException;
    void validatePaymentSource(String fromPlayer, CreditEntry entry) throws CreditException;
    CreditEntry copyCredit(CreditEntry source);
    void commitMutation(CreditEntry draft, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                        List<CreditEventEntry> events, CreditEntry published) throws CreditException;
    List<CreditEventEntry> paymentEvents(CreditEntry entry, Payment payment, double remainingBefore);
    TransactionEntry getPaylog(UUID paylogId) throws CreditException;
    List<CreditEntry> matchingActiveDeals(TransactionEntry paylog);
    boolean samePlayer(String left, String right);
    String normalizeNote(String note) throws CreditException;
    String lower(String value);
    CreditEventEntry event(CreditEventType type, CreditEntry entry, double amount, double amountBefore,
                           String note, String actor, String source, boolean itemPayment);
}
