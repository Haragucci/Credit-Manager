package op.creditmanager.client.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditEntryEditStateTest {
    @Test
    void increasingTheTotalAfterPaymentReopensTheDealAsPartial() {
        CreditEntry entry = new CreditEntry(UUID.randomUUID(), "debtor-creditor", "creditor", "debtor", 100D, null, null);
        entry.addPayment(new Payment(entry.getId(), "debtor", "creditor", 100D, null, "MANUELL"));

        entry.setAmount(150D);
        entry.refreshPaymentState();

        assertEquals("PARTIAL", entry.getStatus());
        assertEquals(50D, entry.getRemainingAmount());
    }

    @Test
    void recoveredPaymentsRebuildTheDerivedPaidAmountAndStatus() {
        CreditEntry entry = new CreditEntry(UUID.randomUUID(), "debtor-creditor", "creditor", "debtor", 100D, null, null);
        entry.replacePayments(List.of(
                new Payment(entry.getId(), "debtor", "creditor", 25D, null, "MIGRATION"),
                new Payment(entry.getId(), "debtor", "creditor", 35D, null, "MIGRATION")));

        assertEquals(60D, entry.getPaidAmount());
        assertEquals(40D, entry.getRemainingAmount());
        assertEquals("PARTIAL", entry.getStatus());
    }
}
