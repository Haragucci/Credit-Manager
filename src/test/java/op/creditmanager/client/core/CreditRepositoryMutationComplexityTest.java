package op.creditmanager.client.core;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditRepositoryMutationComplexityTest {

    @Test
    void singleMutationDoesNotInspectUnrelatedCreditsOrPayments() {
        CreditRepository repository = new CreditRepository();
        CountingCredit[] unrelatedCredits = new CountingCredit[128];
        CountingPayment[] unrelatedPayments = new CountingPayment[128];
        for (int index = 0; index < unrelatedCredits.length; index++) {
            CountingCredit credit = new CountingCredit("unrelated-" + index);
            CountingPayment payment = new CountingPayment(credit.getId(), index + 1L);
            unrelatedCredits[index] = credit;
            unrelatedPayments[index] = payment;
            repository.putCredit(credit);
            repository.putPayment(payment);
        }
        for (CountingCredit credit : unrelatedCredits) credit.reset();
        for (CountingPayment payment : unrelatedPayments) payment.reset();

        CreditEntry affected = new CreditEntry(UUID.randomUUID(), "affected", "creditor", "debtor",
                10_000L, null, null);
        repository.applyCommittedMutation(affected, List.of(), List.of(), List.of(), 5L);
        repository.getPaymentsByCreditId(affected.getId());

        for (CountingCredit credit : unrelatedCredits) assertEquals(0, credit.partyReads);
        for (CountingPayment payment : unrelatedPayments) assertEquals(0, payment.creditIdReads);
    }

    private static final class CountingCredit extends CreditEntry {
        private int partyReads;

        private CountingCredit(String name) {
            super(UUID.randomUUID(), name, name + "-creditor", name + "-debtor", 1_000_000L, null, null);
        }

        @Override
        public String getCreditor() {
            partyReads++;
            return super.getCreditor();
        }

        @Override
        public String getDebtor() {
            partyReads++;
            return super.getDebtor();
        }

        private void reset() {
            partyReads = 0;
        }
    }

    private static final class CountingPayment extends Payment {
        private int creditIdReads;

        private CountingPayment(UUID creditId, long timestamp) {
            super(creditId, "debtor", "creditor", 1L, List.of(), "TEST");
            setTimestamp(timestamp);
        }

        @Override
        public UUID getCreditId() {
            creditIdReads++;
            return super.getCreditId();
        }

        private void reset() {
            creditIdReads = 0;
        }
    }
}
