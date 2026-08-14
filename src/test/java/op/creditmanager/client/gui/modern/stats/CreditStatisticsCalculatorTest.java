package op.creditmanager.client.gui.modern.stats;

import org.junit.jupiter.api.Test;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.money.MoneyRules;

import java.util.List;
import java.util.UUID;
import java.math.BigInteger;

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

        assertEquals(BigInteger.ZERO, statistics.paidClaimsInPeriodMinor());
        assertEquals(BigInteger.ZERO, statistics.createdClaimsInPeriodMinor());
        assertEquals(1, statistics.deletedDealCount());
        assertEquals(1, statistics.actionCount());
    }

    @Test
    void reactivatedArchivedDealContributesAgainWhileClosedAndDeletedPaymentsStayConsistent() {
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "other-me", "me", "other", 100D, null, "");
        CreditEventEntry created = event(CreditEventType.CREDIT_CREATED, credit, 100D, 1L);
        CreditEventEntry payment = event(CreditEventType.PAYMENT_ADDED, credit, 40D, 2L);
        CreditEventEntry deletedPayment = event(CreditEventType.PAYMENT_DELETED, credit, 10D, 3L);
        CreditEventEntry closed = event(CreditEventType.CREDIT_CLOSED, credit, 100D, 4L);
        CreditEventEntry archived = event(CreditEventType.CREDIT_ARCHIVED, credit, 100D, 5L);
        CreditEventEntry reactivated = event(CreditEventType.CREDIT_REACTIVATED, credit, 100D, 6L);

        CreditStatistics statistics = CreditStatisticsCalculator.calculate("me", List.of(), List.of(),
                List.of(created, payment, deletedPayment, closed, archived, reactivated), 0L, Long.MAX_VALUE);

        assertEquals(BigInteger.valueOf(10_000L), statistics.createdClaimsInPeriodMinor());
        assertEquals(BigInteger.valueOf(3_000L), statistics.paidClaimsInPeriodMinor());
        assertEquals(1, statistics.deletedPaymentCount());
        assertEquals(1, statistics.deletedDealCount());
    }

    @Test
    void crossRecordTotalsDoNotOverflowLongMinorUnits() {
        List<CreditEntry> claims = java.util.stream.IntStream.range(0, 93)
                .mapToObj(index -> new CreditEntry(UUID.randomUUID(), "deal-" + index, "me", "other" + index,
                        MoneyRules.MAX_MINOR, null, ""))
                .toList();

        CreditStatistics statistics = CreditStatisticsCalculator.calculate("me", claims, List.of(),
                List.of(), 0L, Long.MAX_VALUE);

        assertEquals(BigInteger.valueOf(MoneyRules.MAX_MINOR).multiply(BigInteger.valueOf(93L)),
                statistics.openClaimsMinor());
    }

    private CreditEventEntry event(CreditEventType type, CreditEntry credit, double amount, long timestamp) {
        CreditEventEntry event = new CreditEventEntry(type, credit, amount, credit.getRemainingAmount(), "", "me", "", false);
        event.setTimestamp(timestamp);
        return event;
    }
}
