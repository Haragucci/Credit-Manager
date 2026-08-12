package op.creditmanager.client.gui.modern.stats;

import java.util.List;

public record CreditStatistics(long openClaimsMinor, long openDebtsMinor, int openClaimCount, int openDebtCount,
                               long paidClaimsInPeriodMinor, long paidDebtsInPeriodMinor, long netChangeMinor,
                               long largestEventMinor, long averageEventMinor, List<HistoryPoint> history,
                               long createdClaimsInPeriodMinor, long createdDebtsInPeriodMinor,
                               int actionCount, int deletedDealCount, int deletedPaymentCount) {
    public long balanceMinor() { return Math.subtractExact(openClaimsMinor, openDebtsMinor); }
    public double ratio() { return openDebtsMinor == 0L ? (openClaimsMinor == 0L ? 0.0D : 1.0D) : (double) openClaimsMinor / openDebtsMinor; }

    public record HistoryPoint(long timestamp, long claimsMinor, long debtsMinor) {
        public long balanceMinor() { return Math.subtractExact(claimsMinor, debtsMinor); }
    }
}
