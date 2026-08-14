package op.creditmanager.client.core;

import java.util.List;
import java.util.UUID;

public record CreditStatisticsSnapshot(String player, List<OpenCredit> claims, List<OpenCredit> debts, long revision) {
    public CreditStatisticsSnapshot {
        player = player == null ? "" : player;
        claims = List.copyOf(claims);
        debts = List.copyOf(debts);
    }

    public record OpenCredit(UUID id, long remainingAmountMinor) { }
}
