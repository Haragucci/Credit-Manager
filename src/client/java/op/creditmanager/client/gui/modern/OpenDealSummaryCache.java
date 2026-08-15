package op.creditmanager.client.gui.modern;

import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditStatisticsSnapshot;
import op.creditmanager.client.money.MoneyAggregate;

import java.math.BigInteger;

final class OpenDealSummaryCache {
    private long revision = Long.MIN_VALUE;
    private String player = "";
    private OpenDealSummary summary;

    OpenDealSummary get(CreditManager manager, String currentPlayer) {
        String normalizedPlayer = currentPlayer == null ? "" : currentPlayer;
        long currentRevision = manager.getRevision();
        if (summary != null && revision == currentRevision && player.equalsIgnoreCase(normalizedPlayer)) return summary;

        CreditStatisticsSnapshot snapshot = manager.getStatisticsSnapshot(normalizedPlayer);
        BigInteger claimTotalMinor = MoneyAggregate.sum(snapshot.claims(),
                CreditStatisticsSnapshot.OpenCredit::remainingAmountMinor);
        BigInteger debtTotalMinor = MoneyAggregate.sum(snapshot.debts(),
                CreditStatisticsSnapshot.OpenCredit::remainingAmountMinor);
        summary = new OpenDealSummary(snapshot.claims().size(), snapshot.debts().size(),
                claimTotalMinor, debtTotalMinor);
        revision = snapshot.revision();
        player = normalizedPlayer;
        return summary;
    }

    record OpenDealSummary(int claimCount, int debtCount,
                           BigInteger claimTotalMinor, BigInteger debtTotalMinor) {
        BigInteger netMinor() {
            return claimTotalMinor.subtract(debtTotalMinor);
        }
    }
}
