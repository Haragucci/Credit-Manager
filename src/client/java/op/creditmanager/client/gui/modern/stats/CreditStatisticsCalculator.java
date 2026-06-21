package op.creditmanager.client.gui.modern.stats;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class CreditStatisticsCalculator {
    private CreditStatisticsCalculator() { }

    public static CreditStatistics calculate(String player, List<CreditEntry> claims, List<CreditEntry> debts,
                                             List<CreditEventEntry> events, long fromInclusive, long toInclusive) {
        double openClaims = claims.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double openDebts = debts.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        String name = player == null ? "" : player.toLowerCase(Locale.ROOT);
        double paidClaims = 0.0;
        double paidDebts = 0.0;
        double largest = 0.0;
        double sum = 0.0;
        int count = 0;
        double createdClaims = 0.0;
        double createdDebts = 0.0;
        int deletedDeals = 0;
        int deletedPayments = 0;
        Set<java.util.UUID> deletedCredits = events.stream()
                .filter(event -> event.getType() == CreditEventType.CREDIT_DELETED && event.getTimestamp() <= toInclusive)
                .map(CreditEventEntry::getCreditId)
                .collect(Collectors.toSet());

        for (CreditEventEntry event : events) {
            if (!involves(event, name) || event.getTimestamp() < fromInclusive || event.getTimestamp() > toInclusive) continue;
            double amount = Math.abs(event.getAmount());
            boolean creditor = name.equals(lower(event.getCreditor()));
            boolean debtor = name.equals(lower(event.getDebtor()));
            switch (event.getType()) {
                case CREDIT_CREATED -> {
                    if (!deletedCredits.contains(event.getCreditId())) {
                        if (creditor) createdClaims += amount;
                        if (debtor) createdDebts += amount;
                        largest = Math.max(largest, amount);
                        sum += amount;
                        count++;
                    }
                }
                case PAYMENT_ADDED, PAYMENT_DELETED -> {
                    if (!deletedCredits.contains(event.getCreditId())) {
                        double sign = event.getType() == CreditEventType.PAYMENT_DELETED ? -1.0 : 1.0;
                        if (creditor) paidClaims += sign * amount;
                        if (debtor) paidDebts += sign * amount;
                        if (event.getType() == CreditEventType.PAYMENT_DELETED) deletedPayments++;
                        largest = Math.max(largest, amount);
                        sum += amount;
                        count++;
                    }
                }
                case CREDIT_DELETED -> {
                    deletedDeals++;
                    count++;
                }
                default -> { }
            }
        }
        return new CreditStatistics(openClaims, openDebts, claims.size(), debts.size(), paidClaims, paidDebts,
                (createdClaims - createdDebts) + (paidDebts - paidClaims), largest,
                count == 0 ? 0.0 : sum / count, List.of(), createdClaims, createdDebts,
                count, deletedDeals, deletedPayments);
    }

    private static boolean involves(CreditEventEntry event, String player) {
        return player.equals(lower(event.getCreditor())) || player.equals(lower(event.getDebtor()));
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
