package op.creditmanager.client.paylog.importer;

import op.creditmanager.client.core.validation.CreditValidationRules;
import op.creditmanager.client.money.MoneyRules;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BankPaylogItemParser {
    public static final ZoneId OPSUCHT_BANK_ZONE = ZoneId.of("Europe/Berlin");
    private static final Pattern DESCRIPTION = Pattern.compile(
            "^\\s*Beschreibung\\s*:\\s*Überweisung\\s+(von|an)(?:\\s+(.*?))?\\s*$");
    private static final Pattern DESCRIPTION_MARKER = Pattern.compile(
            "^\\s*Beschreibung\\s*:\\s*Überweisung\\s+(?:von|an)(?:\\s|$).*");
    private static final Pattern TIMESTAMP = Pattern.compile("\\b(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})\\b");
    private static final Pattern MERGED = Pattern.compile("^\\s*Zusammengeführte\\s+Transaktionen\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern MERGED_MARKER = Pattern.compile("^\\s*Zusammengeführte\\s+Transaktionen\\b.*");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("dd.MM.uuuu HH:mm:ss", Locale.GERMAN)
            .withResolverStyle(ResolverStyle.STRICT);

    public ParseResult parse(String visibleName, List<String> loreLines, String selfPlayer,
                             long scanOrder, int pageNumber, int slotId) {
        List<String> lore = loreLines == null ? List.of() : List.copyOf(loreLines);
        Map<String, String> counterparties = new LinkedHashMap<>();
        DescriptionDirection descriptionDirection = null;
        boolean relevant = false;
        for (String line : lore) {
            String value = line == null ? "" : line;
            if (!DESCRIPTION_MARKER.matcher(value).matches()) continue;
            relevant = true;
            Matcher description = DESCRIPTION.matcher(value);
            if (!description.matches() || description.group(2) == null) {
                return ParseResult.error("Gegenpartei fehlt");
            }
            DescriptionDirection parsedDirection = "an".equals(description.group(1))
                    ? DescriptionDirection.OUTGOING : DescriptionDirection.INCOMING;
            if (descriptionDirection != null && descriptionDirection != parsedDirection) {
                return ParseResult.error("Widersprüchliche Überweisungsrichtungen");
            }
            descriptionDirection = parsedDirection;
            String counterparty = description.group(2).trim();
            if (!CreditValidationRules.isValidPlayerName(counterparty)) {
                return ParseResult.error("Ungültiger Spielername in Überweisungsbeschreibung");
            }
            counterparties.putIfAbsent(counterparty.toLowerCase(Locale.ROOT), counterparty);
        }
        if (!relevant) return ParseResult.ignored();
        if (counterparties.size() != 1) return ParseResult.error("Widersprüchliche Gegenparteien");

        String self = selfPlayer == null ? "" : selfPlayer.trim().toLowerCase(Locale.ROOT);
        if (!CreditValidationRules.isValidPlayerName(self)) return ParseResult.error("Eigener Spielername ist ungültig");
        String counterparty = counterparties.values().iterator().next();
        if (counterparty.equalsIgnoreCase(self)) return ParseResult.error("Gegenpartei entspricht dem eigenen Spieler");

        Amount amount = parseAmount(visibleName).orElse(null);
        if (amount == null) return ParseResult.error("Betrag ist nicht parsebar oder nicht positiv");

        Map<String, Long> timestamps = new LinkedHashMap<>();
        for (String line : lore) {
            Matcher matcher = TIMESTAMP.matcher(line == null ? "" : line);
            while (matcher.find()) {
                String raw = matcher.group(1).replaceAll("\\s+", " ");
                Long parsed = parseTimestamp(raw);
                if (parsed == null) return ParseResult.error("Sekunden-Timestamp ist ungültig");
                timestamps.putIfAbsent(raw, parsed);
            }
        }
        if (timestamps.isEmpty()) return ParseResult.error("Sekunden-Timestamp fehlt");
        if (timestamps.size() != 1 || timestamps.values().stream().distinct().count() != 1L) {
            return ParseResult.error("Widersprüchliche Zeitstempel");
        }

        Integer mergedCount = parseMergedCount(lore);
        if (mergedCount == null) return ParseResult.error("Zusammengeführte Transaktionen sind ungültig");

        boolean outgoing = descriptionDirection == DescriptionDirection.OUTGOING || amount.outgoing();
        String from = outgoing ? self : counterparty;
        String to = outgoing ? counterparty : self;
        BankPaylogImportCandidate.Direction direction = outgoing
                ? BankPaylogImportCandidate.Direction.OUTGOING
                : BankPaylogImportCandidate.Direction.INCOMING;
        Map.Entry<String, Long> timestamp = timestamps.entrySet().iterator().next();
        try {
            return ParseResult.candidate(new BankPaylogImportCandidate(from, to, amount.amountMinor(),
                    timestamp.getValue(), timestamp.getKey(), counterparty, direction, mergedCount,
                    scanOrder, pageNumber, slotId));
        } catch (IllegalArgumentException exception) {
            return ParseResult.error(exception.getMessage());
        }
    }

    private Optional<Amount> parseAmount(String visibleName) {
        if (visibleName == null || visibleName.isBlank()) return Optional.empty();
        String raw = visibleName.trim();
        boolean outgoing = raw.charAt(0) == '-';
        if (outgoing || raw.charAt(0) == '+') raw = raw.substring(1).trim();
        if (raw.isBlank()) return Optional.empty();
        return MoneyRules.parse(raw)
                .filter(value -> MoneyRules.isPositive(value.minorUnits()))
                .map(value -> new Amount(value.minorUnits(), outgoing));
    }

    private Long parseTimestamp(String raw) {
        try {
            LocalDateTime local = LocalDateTime.parse(raw, TIMESTAMP_FORMAT);
            ZonedDateTime zoned = local.atZone(OPSUCHT_BANK_ZONE);
            if (!TIMESTAMP_FORMAT.format(zoned).equals(raw)) return null;
            return zoned.toInstant().toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException exception) {
            return null;
        }
    }

    private Integer parseMergedCount(List<String> lore) {
        Integer count = 1;
        boolean found = false;
        for (String line : lore) {
            String value = line == null ? "" : line;
            if (!MERGED_MARKER.matcher(value).matches()) continue;
            Matcher matcher = MERGED.matcher(value);
            if (!matcher.matches()) return null;
            try {
                int parsed = Integer.parseInt(matcher.group(1));
                if (parsed < 1 || found && parsed != count) return null;
                count = parsed;
                found = true;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return count;
    }

    private record Amount(long amountMinor, boolean outgoing) { }

    private enum DescriptionDirection {
        INCOMING,
        OUTGOING
    }

    public record ParseResult(Status status, BankPaylogImportCandidate candidate, String error) {
        public ParseResult {
            if (status == null) throw new IllegalArgumentException("Parserstatus fehlt");
            error = error == null ? "" : error;
        }

        public static ParseResult ignored() { return new ParseResult(Status.IGNORED, null, ""); }
        public static ParseResult candidate(BankPaylogImportCandidate candidate) {
            return new ParseResult(Status.CANDIDATE, candidate, "");
        }
        public static ParseResult error(String error) { return new ParseResult(Status.ERROR, null, error); }
    }

    public enum Status {
        IGNORED,
        CANDIDATE,
        ERROR
    }
}
