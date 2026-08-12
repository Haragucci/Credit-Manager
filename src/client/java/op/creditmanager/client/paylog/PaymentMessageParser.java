package op.creditmanager.client.paylog;

import op.creditmanager.client.money.MoneyRules;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaymentMessageParser {
    private static final Pattern FORMAT_CODES = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private static final String AMOUNT = "([\\d.,]+(?:mrd|mio|kk|k|m|b)?)";
    private static final Pattern OUTGOING = Pattern.compile("^\\s*OPSUCHT\\s*»\\s*Du hast\\s+([^\\s]+)\\s+" + AMOUNT + "\\$ gegeben\\.?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCOMING = Pattern.compile("^\\s*OPSUCHT\\s*»\\s*([^\\s]+) hat dir\\s+" + AMOUNT + "\\$ gegeben\\.?\\s*$", Pattern.CASE_INSENSITIVE);

    public Optional<DetectedPayment> parse(String message, String currentPlayerName) {
        if (message == null || currentPlayerName == null || currentPlayerName.isBlank()) return Optional.empty();
        String normalized = FORMAT_CODES.matcher(message).replaceAll("").trim();
        if (normalized.length() > 16_384) return Optional.empty();
        String currentPlayer = currentPlayerName.toLowerCase(Locale.ROOT);
        Matcher outgoing = OUTGOING.matcher(normalized);
        if (outgoing.matches()) return payment(currentPlayer, outgoing.group(1), outgoing.group(2), normalized);
        Matcher incoming = INCOMING.matcher(normalized);
        if (incoming.matches()) return payment(incoming.group(1), currentPlayer, incoming.group(2), normalized);
        return Optional.empty();
    }

    private Optional<DetectedPayment> payment(String fromPlayer, String toPlayer, String rawAmount, String message) {
        return MoneyRules.parse(rawAmount).map(amount -> new DetectedPayment(fromPlayer.toLowerCase(Locale.ROOT),
                toPlayer.toLowerCase(Locale.ROOT), amount.minorUnits(), message));
    }
}
