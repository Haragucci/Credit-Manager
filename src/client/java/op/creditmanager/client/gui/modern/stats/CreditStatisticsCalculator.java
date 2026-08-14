package op.creditmanager.client.gui.modern.stats;

import op.creditmanager.client.core.CreditStatisticsSnapshot;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.money.MoneyAggregate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CreditStatisticsCalculator {
    private CreditStatisticsCalculator() { }

    public static CreditStatistics calculate(String player, List<CreditEntry> claims, List<CreditEntry> debts,
                                             List<CreditEventEntry> events, long fromInclusive, long toInclusive) {
        Set<java.util.UUID> inactiveCredits = inactiveCredits(events, toInclusive);
        return calculate(player, claims, debts, events, inactiveCredits, fromInclusive, toInclusive);
    }

    public static CreditStatistics calculate(String player, List<CreditEntry> claims, List<CreditEntry> debts,
                                             List<CreditEventEntry> events, Set<java.util.UUID> inactiveCredits,
                                             long fromInclusive, long toInclusive) {
        List<CreditStatisticsSnapshot.OpenCredit> claimSnapshots = claims.stream()
                .map(credit -> new CreditStatisticsSnapshot.OpenCredit(credit.getId(), credit.getRemainingAmountMinor()))
                .toList();
        List<CreditStatisticsSnapshot.OpenCredit> debtSnapshots = debts.stream()
                .map(credit -> new CreditStatisticsSnapshot.OpenCredit(credit.getId(), credit.getRemainingAmountMinor()))
                .toList();
        return calculateSnapshot(player, claimSnapshots, debtSnapshots, events, inactiveCredits, fromInclusive, toInclusive);
    }

    public static CreditStatistics calculate(CreditStatisticsSnapshot snapshot, List<CreditEventEntry> events,
                                             Set<java.util.UUID> inactiveCredits, long fromInclusive, long toInclusive) {
        return calculateSnapshot(snapshot.player(), snapshot.claims(), snapshot.debts(), events, inactiveCredits,
                fromInclusive, toInclusive);
    }

    private static CreditStatistics calculateSnapshot(String player, List<CreditStatisticsSnapshot.OpenCredit> claims,
                                                       List<CreditStatisticsSnapshot.OpenCredit> debts,
                                                       List<CreditEventEntry> events, Set<java.util.UUID> inactiveCredits,
                                                       long fromInclusive, long toInclusive) {
        BigInteger openClaimsMinor = MoneyAggregate.sum(claims, CreditStatisticsSnapshot.OpenCredit::remainingAmountMinor);
        BigInteger openDebtsMinor = MoneyAggregate.sum(debts, CreditStatisticsSnapshot.OpenCredit::remainingAmountMinor);
        String name = player == null ? "" : player.toLowerCase(Locale.ROOT);
        BigInteger paidClaimsMinor = BigInteger.ZERO;
        BigInteger paidDebtsMinor = BigInteger.ZERO;
        BigInteger largestMinor = BigInteger.ZERO;
        BigInteger sumMinor = BigInteger.ZERO;
        int count = 0;
        BigInteger createdClaimsMinor = BigInteger.ZERO;
        BigInteger createdDebtsMinor = BigInteger.ZERO;
        int deletedDeals = 0;
        int deletedPayments = 0;
        Set<java.util.UUID> deletedCredits = inactiveCredits == null ? Set.of() : Set.copyOf(inactiveCredits);

        for (CreditEventEntry event : events) {
            if (!involves(event, name) || event.getTimestamp() < fromInclusive || event.getTimestamp() > toInclusive) continue;
            BigInteger amountMinor = BigInteger.valueOf(event.getAmountMinor()).abs();
            boolean creditor = name.equals(lower(event.getCreditor()));
            boolean debtor = name.equals(lower(event.getDebtor()));
            switch (event.getType()) {
                case CREDIT_CREATED -> {
                    if (!deletedCredits.contains(event.getCreditId())) {
                        if (creditor) createdClaimsMinor = createdClaimsMinor.add(amountMinor);
                        if (debtor) createdDebtsMinor = createdDebtsMinor.add(amountMinor);
                        largestMinor = largestMinor.max(amountMinor);
                        sumMinor = sumMinor.add(amountMinor);
                        count++;
                    }
                }
                case PAYMENT_ADDED, PAYMENT_DELETED -> {
                    if (!deletedCredits.contains(event.getCreditId())) {
                        BigInteger signedAmountMinor = event.getType() == CreditEventType.PAYMENT_DELETED ? amountMinor.negate() : amountMinor;
                        if (creditor) paidClaimsMinor = paidClaimsMinor.add(signedAmountMinor);
                        if (debtor) paidDebtsMinor = paidDebtsMinor.add(signedAmountMinor);
                        if (event.getType() == CreditEventType.PAYMENT_DELETED) deletedPayments++;
                        largestMinor = largestMinor.max(amountMinor);
                        sumMinor = sumMinor.add(amountMinor);
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
        BigInteger createdNetMinor = createdClaimsMinor.subtract(createdDebtsMinor);
        BigInteger paymentNetMinor = paidDebtsMinor.subtract(paidClaimsMinor);
        return new CreditStatistics(openClaimsMinor, openDebtsMinor, claims.size(), debts.size(), paidClaimsMinor, paidDebtsMinor,
                createdNetMinor.add(paymentNetMinor), largestMinor,
                count == 0 ? BigInteger.ZERO : new BigDecimal(sumMinor).divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP).toBigIntegerExact(),
                List.of(), createdClaimsMinor, createdDebtsMinor,
                count, deletedDeals, deletedPayments);
    }

    private static Set<java.util.UUID> inactiveCredits(List<CreditEventEntry> events, long toInclusive) {
        Set<java.util.UUID> inactive = new java.util.HashSet<>();
        events.stream().filter(event -> event.getTimestamp() <= toInclusive)
                .sorted(java.util.Comparator.comparingLong(CreditEventEntry::getTimestamp)
                        .thenComparing(event -> String.valueOf(event.getId())))
                .forEach(event -> {
                    if (event.getType() == CreditEventType.CREDIT_DELETED || event.getType() == CreditEventType.CREDIT_ARCHIVED) {
                        inactive.add(event.getCreditId());
                    } else if (event.getType() == CreditEventType.CREDIT_REACTIVATED) {
                        inactive.remove(event.getCreditId());
                    }
                });
        return Set.copyOf(inactive);
    }

    private static boolean involves(CreditEventEntry event, String player) {
        return player.equals(lower(event.getCreditor())) || player.equals(lower(event.getDebtor()));
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
