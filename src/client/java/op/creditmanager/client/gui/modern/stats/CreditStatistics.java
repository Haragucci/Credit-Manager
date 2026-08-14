package op.creditmanager.client.gui.modern.stats;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

public record CreditStatistics(BigInteger openClaimsMinor, BigInteger openDebtsMinor, int openClaimCount, int openDebtCount,
                               BigInteger paidClaimsInPeriodMinor, BigInteger paidDebtsInPeriodMinor, BigInteger netChangeMinor,
                               BigInteger largestEventMinor, BigInteger averageEventMinor, List<HistoryPoint> history,
                               BigInteger createdClaimsInPeriodMinor, BigInteger createdDebtsInPeriodMinor,
                               int actionCount, int deletedDealCount, int deletedPaymentCount) {
    public BigInteger balanceMinor() { return openClaimsMinor.subtract(openDebtsMinor); }
    public double ratio() {
        if (openDebtsMinor.signum() == 0) return openClaimsMinor.signum() == 0 ? 0.0D : 1.0D;
        return new BigDecimal(openClaimsMinor).divide(new BigDecimal(openDebtsMinor), 12, RoundingMode.HALF_UP).doubleValue();
    }

    public record HistoryPoint(long timestamp, BigInteger claimsMinor, BigInteger debtsMinor) {
        public BigInteger balanceMinor() { return claimsMinor.subtract(debtsMinor); }
    }
}
