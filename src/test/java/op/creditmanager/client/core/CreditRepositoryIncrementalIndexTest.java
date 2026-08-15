package op.creditmanager.client.core;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditRepositoryIncrementalIndexTest {

    @Test
    void updatesPlayerRolesIncrementallyAcrossCaseAndPartyChanges() {
        CreditRepository repository = new CreditRepository();
        UUID id = UUID.randomUUID();
        CreditEntry original = new CreditEntry(id, "deal", "Creditor", "Debtor", 10_000L, null, null);

        repository.putCredit(original);

        assertEquals(List.of(id), repository.getCreditsByCreditor("CREDITOR").stream().map(CreditEntry::getId).toList());
        assertEquals(List.of(id), repository.getCreditsByDebtor("debtor").stream().map(CreditEntry::getId).toList());

        CreditEntry replacement = new CreditEntry(id, "deal", "NewCreditor", "NewDebtor", 10_000L, null, null);
        repository.replaceLoadedCredit(replacement);

        assertTrue(repository.getCreditsByCreditor("creditor").isEmpty());
        assertTrue(repository.getCreditsByDebtor("DEBTOR").isEmpty());
        assertEquals(List.of(id), repository.getCreditsByCreditor("newcreditor").stream().map(CreditEntry::getId).toList());
        assertEquals(List.of(id), repository.getCreditsByDebtor("NEWDEBTOR").stream().map(CreditEntry::getId).toList());

        repository.deleteCredit(id);

        assertTrue(repository.getCreditsByCreditor("newcreditor").isEmpty());
        assertTrue(repository.getCreditsByDebtor("newdebtor").isEmpty());
    }

    @Test
    void maintainsSortedPaymentBucketsForInsertReplaceMoveAndDelete() {
        CreditRepository repository = new CreditRepository();
        CreditEntry firstCredit = credit("first", 50_000L);
        CreditEntry secondCredit = credit("second", 50_000L);
        repository.putCredit(firstCredit);
        repository.putCredit(secondCredit);

        Payment later = payment(firstCredit.getId(), 2_000L, 200L);
        Payment earlier = payment(firstCredit.getId(), 1_000L, 100L);
        repository.putPayment(later);
        repository.putPayment(earlier);

        assertEquals(List.of(earlier.getId(), later.getId()), repository.getPaymentsByCreditId(firstCredit.getId())
                .stream().map(Payment::getId).toList());
        assertEquals(3_000L, firstCredit.getPaidAmountMinor());

        Payment moved = payment(secondCredit.getId(), 1_500L, 50L);
        moved.setId(earlier.getId());
        repository.putPayment(moved);

        assertEquals(List.of(later.getId()), repository.getPaymentsByCreditId(firstCredit.getId())
                .stream().map(Payment::getId).toList());
        assertEquals(List.of(moved.getId()), repository.getPaymentsByCreditId(secondCredit.getId())
                .stream().map(Payment::getId).toList());
        assertEquals(2_000L, firstCredit.getPaidAmountMinor());
        assertEquals(1_500L, secondCredit.getPaidAmountMinor());

        repository.deletePayment(moved.getId());

        assertTrue(repository.getPaymentsByCreditId(secondCredit.getId()).isEmpty());
        assertEquals(0L, secondCredit.getPaidAmountMinor());
    }

    @Test
    void committedMutationPublishesOnlyAffectedCreditAndPaymentBucket() {
        CreditRepository repository = new CreditRepository();
        CreditEntry unaffected = credit("unaffected", 30_000L);
        CreditEntry affected = credit("affected", 30_000L);
        repository.putCredit(unaffected);
        repository.putCredit(affected);
        Payment existing = payment(unaffected.getId(), 2_000L, 5L);
        repository.putPayment(existing);

        CreditEntry draft = credit(affected.getId(), "affected", 30_000L);
        Payment added = payment(affected.getId(), 4_000L, 10L);
        draft.addPayment(added);

        repository.applyCommittedMutation(draft, List.of(added), List.of(), List.of(), 77L);

        assertEquals(77L, repository.getRevision());
        assertEquals(List.of(existing.getId()), repository.getPaymentsByCreditId(unaffected.getId())
                .stream().map(Payment::getId).toList());
        assertEquals(List.of(added.getId()), repository.getPaymentsByCreditId(affected.getId())
                .stream().map(Payment::getId).toList());
        assertEquals(4_000L, repository.findCreditById(affected.getId()).orElseThrow().getPaidAmountMinor());
    }

    private static CreditEntry credit(String name, long amount) {
        return credit(UUID.randomUUID(), name, amount);
    }

    private static CreditEntry credit(UUID id, String name, long amount) {
        return new CreditEntry(id, name, name + "-creditor", name + "-debtor", amount, null, null);
    }

    private static Payment payment(UUID creditId, long amount, long timestamp) {
        Payment payment = new Payment(creditId, "debtor", "creditor", amount, List.of(), "TEST");
        payment.setTimestamp(timestamp);
        return payment;
    }
}
