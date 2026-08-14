package op.creditmanager.client.gui.modern;

import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.money.MoneyAggregate;

import java.math.BigInteger;
import java.util.List;

final class OpenDealSummaryCache {
    private long revision = Long.MIN_VALUE;
    private String player = "";
    private OpenDealSummary summary;

    OpenDealSummary get(CreditManager manager, String currentPlayer) {
        String normalizedPlayer = currentPlayer == null ? "" : currentPlayer;
        long currentRevision = manager.getRevision();
        if (summary != null && revision == currentRevision && player.equalsIgnoreCase(normalizedPlayer)) return summary;
        List<CreditEntry> claims = List.copyOf(manager.getOpenCreditsAsCreditor(normalizedPlayer));
        List<CreditEntry> debts = List.copyOf(manager.getOpenCreditsAsDebtor(normalizedPlayer));
        summary = new OpenDealSummary(claims, debts,
                MoneyAggregate.sum(claims, CreditEntry::getRemainingAmountMinor),
                MoneyAggregate.sum(debts, CreditEntry::getRemainingAmountMinor));
        revision = currentRevision;
        player = normalizedPlayer;
        return summary;
    }

    record OpenDealSummary(List<CreditEntry> claims, List<CreditEntry> debts,
                           BigInteger claimTotalMinor, BigInteger debtTotalMinor) {
        BigInteger netMinor() {
            return claimTotalMinor.subtract(debtTotalMinor);
        }
    }
}
