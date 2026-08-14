package op.creditmanager.client.search;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DealSearchText {
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter GERMAN_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");

    private DealSearchText() { }

    public static String build(CreditEntry entry) {
        if (entry == null) return "";
        List<String> values = new ArrayList<>();
        values.add(safe(entry.getDealName()));
        values.add(safe(entry.getDebtor()));
        values.add(safe(entry.getCreditor()));
        values.add(safe(entry.getStatus()));
        values.add(statusWords(entry));
        values.add(safe(entry.getNote()));
        values.add(entry.getId() == null ? "" : entry.getId().toString());
        if (entry.getId() != null) values.add(entry.getId().toString().substring(0, 8));
        values.add(amountWords(entry.getAmountMinor()));
        values.add(amountWords(entry.getPaidAmountMinor()));
        values.add(amountWords(entry.getRemainingAmountMinor()));
        values.add(dateWords(entry.getCreatedAt()));
        if (entry.getCompletedAt() != null) values.add(dateWords(entry.getCompletedAt()));
        if (entry.getDueDate() != null) values.add(dateWords(entry.getDueDate()));
        return SearchNormalizer.normalize(String.join(" ", values));
    }

    public static List<String> tokens(String query) {
        String normalized = SearchNormalizer.normalize(query);
        return normalized.isBlank() ? List.of() : List.of(normalized.split("\\s+"));
    }

    private static String amountWords(long amountMinor) {
        String exact = java.math.BigDecimal.valueOf(amountMinor, 2).toPlainString();
        String wholeAmount = amountMinor % 100L == 0L ? Long.toString(amountMinor / 100L) : "";
        return wholeAmount + " " + exact + " " + FormatUtil.formatAmountMinor(amountMinor) + " "
                + FormatUtil.formatChartAmountMinor(amountMinor, false);
    }

    private static String dateWords(long timestamp) {
        if (timestamp <= 0) return "";
        ZonedDateTime date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault());
        return date.toLocalDate() + " " + GERMAN_DATE.format(date) + " " + GERMAN_DATE_TIME.format(date)
                + " " + timestamp;
    }

    private static String statusWords(CreditEntry entry) {
        String status = switch (safe(entry.getStatus()).toUpperCase(Locale.ROOT)) {
            case "PAID" -> "bezahlt paid";
            case "PARTIAL" -> "teilweise teilzahlung partial";
            case "CLOSED" -> "abgeschlossen geschlossen closed";
            case "CANCELLED" -> "storniert cancelled";
            default -> "offen open";
        };
        return entry.isArchived() ? status + " archiviert archive" : status;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
