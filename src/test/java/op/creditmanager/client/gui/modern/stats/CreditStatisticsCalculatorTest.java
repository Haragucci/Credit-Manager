package op.creditmanager.client.gui.modern.stats;

import org.junit.jupiter.api.Test;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditStatisticsCalculatorTest {
    @Test
    void deletedDealDoesNotLeavePaymentTotalsBehind() {
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "other-me", "me", "other", 100D, null, "");
        CreditEventEntry created = new CreditEventEntry(CreditEventType.CREDIT_CREATED, credit, 100D, 100D, "", "me", "", false);
        CreditEventEntry payment = new CreditEventEntry(CreditEventType.PAYMENT_ADDED, credit, 40D, 100D, "", "other", "", false);
        CreditEventEntry deleted = new CreditEventEntry(CreditEventType.CREDIT_DELETED, credit, 100D, 60D, "", "me", "", false);

        CreditStatistics statistics = CreditStatisticsCalculator.calculate("me", List.of(), List.of(),
                List.of(created, payment, deleted), 0L, Long.MAX_VALUE);

        assertEquals(0D, statistics.paidClaimsInPeriod());
        assertEquals(0D, statistics.createdClaimsInPeriod());
        assertEquals(1, statistics.deletedDealCount());
        assertEquals(1, statistics.actionCount());
    }
}
