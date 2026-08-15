package op.creditmanager.client.paylog.importer;

import op.creditmanager.client.core.validation.CreditValidationRules;
import op.creditmanager.client.money.MoneyRules;

import java.util.Locale;

public record BankPaylogImportCandidate(
        String fromPlayer,
        String toPlayer,
        long amountMinor,
        long timestamp,
        String rawTimestamp,
        String counterparty,
        Direction direction,
        int mergedTransactionCount,
        long scanOrder,
        int pageNumber,
        int slotId
) {
    public BankPaylogImportCandidate {
        fromPlayer = normalizePlayer(fromPlayer);
        toPlayer = normalizePlayer(toPlayer);
        rawTimestamp = rawTimestamp == null ? "" : rawTimestamp.trim();
        counterparty = counterparty == null ? "" : counterparty.trim();
        if (!CreditValidationRules.isValidPlayerName(fromPlayer)
                || !CreditValidationRules.isValidPlayerName(toPlayer)
                || fromPlayer.equalsIgnoreCase(toPlayer)) {
            throw new IllegalArgumentException("Ungültige Parteien im Bank-Paylog");
        }
        if (!MoneyRules.isPositive(amountMinor) || timestamp <= 0L || rawTimestamp.isBlank()
                || !CreditValidationRules.isValidPlayerName(counterparty) || direction == null
                || mergedTransactionCount < 1 || scanOrder < 0L || pageNumber < 1 || slotId < 0) {
            throw new IllegalArgumentException("Ungültiger Bank-Paylog-Kandidat");
        }
    }

    public String deterministicRawText() {
        String amount = MoneyRules.toPlainMajor(amountMinor).replace('.', ',') + '$';
        String description = direction == Direction.OUTGOING ? "Überweisung an " : "Überweisung von ";
        return "[Bank-Import] " + fromPlayer + " -> " + toPlayer + ": " + amount
                + " | " + rawTimestamp + " | " + description + counterparty;
    }

    private static String normalizePlayer(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum Direction {
        INCOMING,
        OUTGOING
    }
}
