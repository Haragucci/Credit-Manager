package op.creditmanager.client.gui.modern.stats;

import java.util.List;

public record CreditStatistics(double openClaims, double openDebts, int openClaimCount, int openDebtCount,
                               double paidClaimsInPeriod, double paidDebtsInPeriod, double netChange,
                               double largestEvent, double averageEvent, List<HistoryPoint> history,
                               double createdClaimsInPeriod, double createdDebtsInPeriod,
                               int actionCount, int deletedDealCount, int deletedPaymentCount) {
    public double balance() { return openClaims - openDebts; }
    public double ratio() { return openDebts == 0.0 ? (openClaims == 0.0 ? 0.0 : 1.0) : openClaims / openDebts; }

    public record HistoryPoint(long timestamp, double claims, double debts) {
        public double balance() { return claims - debts; }
    }
}
