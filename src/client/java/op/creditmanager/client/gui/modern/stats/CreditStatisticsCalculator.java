package op.creditmanager.client.gui.modern.stats;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds live totals plus an event-derived development graph from credit_events.json only. */
public final class CreditStatisticsCalculator {
    private CreditStatisticsCalculator() {
    }

    public static CreditStatistics calculate(String player, List<CreditEntry> claims, List<CreditEntry> debts,
                                             List<CreditEventEntry> events, long fromInclusive, long toInclusive) {
        double openClaims = claims.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double openDebts = debts.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        String name = player == null ? "" : player.toLowerCase(Locale.ROOT);
        List<CreditEventEntry> sorted = events.stream().filter(event -> involves(event, name))
                .sorted(Comparator.comparingLong(CreditEventEntry::getTimestamp)).toList();
        double paidClaims = 0.0;
        double paidDebts = 0.0;
        double largest = 0.0;
        double sum = 0.0;
        int count = 0;
        Map<java.util.UUID, Snapshot> states = new HashMap<>();
        List<CreditStatistics.HistoryPoint> history = new ArrayList<>();
        for (CreditEventEntry event : sorted) {
            boolean owner = name.equals(lower(event.getCreditor()));
            boolean debtor = name.equals(lower(event.getDebtor()));
            if (event.getCreditId() != null) states.put(event.getCreditId(), new Snapshot(owner, debtor, event.getRemainingAmountAfter()));
            if (event.getTimestamp() >= fromInclusive && event.getTimestamp() <= toInclusive) {
                double amount = Math.abs(event.getAmount());
                if (event.getType() == CreditEventType.PAYMENT_ADDED) {
                    if (owner) paidClaims += amount;
                    if (debtor) paidDebts += amount;
                }
                if (event.getType() != CreditEventType.CREDIT_DELETED) {
                    largest = Math.max(largest, amount);
                    sum += amount;
                    count++;
                }
                double graphClaims = states.values().stream().filter(Snapshot::owner).mapToDouble(Snapshot::remaining).sum();
                double graphDebts = states.values().stream().filter(Snapshot::debtor).mapToDouble(Snapshot::remaining).sum();
                history.add(new CreditStatistics.HistoryPoint(event.getTimestamp(), graphClaims, graphDebts));
            }
        }
        return new CreditStatistics(openClaims, openDebts, claims.size(), debts.size(), paidClaims, paidDebts,
                paidClaims - paidDebts, largest, count == 0 ? 0.0 : sum / count, compact(history));
    }

    private static List<CreditStatistics.HistoryPoint> compact(List<CreditStatistics.HistoryPoint> points) {
        if (points.size() <= 18) return points;
        List<CreditStatistics.HistoryPoint> compact = new ArrayList<>();
        double step = (points.size() - 1) / 17.0;
        for (int index = 0; index < 18; index++) compact.add(points.get((int) Math.round(index * step)));
        return compact;
    }

    private static boolean involves(CreditEventEntry event, String player) {
        return player.equals(lower(event.getCreditor())) || player.equals(lower(event.getDebtor()));
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private record Snapshot(boolean owner, boolean debtor, double remaining) { }
}
