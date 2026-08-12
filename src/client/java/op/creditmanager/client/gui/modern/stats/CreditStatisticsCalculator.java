package op.creditmanager.client.gui.modern.stats;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CreditStatisticsCalculator {
    private CreditStatisticsCalculator() { }

    public static CreditStatistics calculate(String player, List<CreditEntry> claims, List<CreditEntry> debts,
                                             List<CreditEventEntry> events, long fromInclusive, long toInclusive) {
        long openClaimsMinor = sumRemainingMinor(claims);
        long openDebtsMinor = sumRemainingMinor(debts);
        String name = player == null ? "" : player.toLowerCase(Locale.ROOT);
        long paidClaimsMinor = 0L;
        long paidDebtsMinor = 0L;
        long largestMinor = 0L;
        long sumMinor = 0L;
        int count = 0;
        long createdClaimsMinor = 0L;
        long createdDebtsMinor = 0L;
        int deletedDeals = 0;
        int deletedPayments = 0;
        Set<java.util.UUID> deletedCredits = new java.util.HashSet<>();
        events.stream().filter(event -> event.getTimestamp() <= toInclusive)
                .sorted(java.util.Comparator.comparingLong(CreditEventEntry::getTimestamp))
                .forEach(event -> {
                    if (event.getType() == CreditEventType.CREDIT_DELETED || event.getType() == CreditEventType.CREDIT_ARCHIVED) deletedCredits.add(event.getCreditId());
                    else if (event.getType() == CreditEventType.CREDIT_REACTIVATED) deletedCredits.remove(event.getCreditId());
                });

        for (CreditEventEntry event : events) {
            if (!involves(event, name) || event.getTimestamp() < fromInclusive || event.getTimestamp() > toInclusive) continue;
            long amountMinor = Math.abs(event.getAmountMinor());
            boolean creditor = name.equals(lower(event.getCreditor()));
            boolean debtor = name.equals(lower(event.getDebtor()));
            switch (event.getType()) {
                case CREDIT_CREATED -> {
                    if (!deletedCredits.contains(event.getCreditId())) {
                        if (creditor) createdClaimsMinor = Math.addExact(createdClaimsMinor, amountMinor);
                        if (debtor) createdDebtsMinor = Math.addExact(createdDebtsMinor, amountMinor);
                        largestMinor = Math.max(largestMinor, amountMinor);
                        sumMinor = Math.addExact(sumMinor, amountMinor);
                        count++;
                    }
                }
                case PAYMENT_ADDED, PAYMENT_DELETED -> {
                    if (!deletedCredits.contains(event.getCreditId())) {
                        long signedAmountMinor = event.getType() == CreditEventType.PAYMENT_DELETED ? -amountMinor : amountMinor;
                        if (creditor) paidClaimsMinor = Math.addExact(paidClaimsMinor, signedAmountMinor);
                        if (debtor) paidDebtsMinor = Math.addExact(paidDebtsMinor, signedAmountMinor);
                        if (event.getType() == CreditEventType.PAYMENT_DELETED) deletedPayments++;
                        largestMinor = Math.max(largestMinor, amountMinor);
                        sumMinor = Math.addExact(sumMinor, amountMinor);
                        count++;
                    }
                }
                case CREDIT_DELETED, CREDIT_ARCHIVED -> {
                    deletedDeals++;
                    count++;
                }
                default -> { }
            }
        }
        long createdNetMinor = Math.subtractExact(createdClaimsMinor, createdDebtsMinor);
        long paymentNetMinor = Math.subtractExact(paidDebtsMinor, paidClaimsMinor);
        return new CreditStatistics(openClaimsMinor, openDebtsMinor, claims.size(), debts.size(), paidClaimsMinor, paidDebtsMinor,
                Math.addExact(createdNetMinor, paymentNetMinor), largestMinor,
                count == 0 ? 0L : java.math.BigDecimal.valueOf(sumMinor).divide(java.math.BigDecimal.valueOf(count), 0, java.math.RoundingMode.HALF_UP).longValueExact(),
                List.of(), createdClaimsMinor, createdDebtsMinor,
                count, deletedDeals, deletedPayments);
    }

    private static long sumRemainingMinor(List<CreditEntry> entries) {
        long sum = 0L;
        for (CreditEntry entry : entries) sum = Math.addExact(sum, entry.getRemainingAmountMinor());
        return sum;
    }

    private static boolean involves(CreditEventEntry event, String player) {
        return player.equals(lower(event.getCreditor())) || player.equals(lower(event.getDebtor()));
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
