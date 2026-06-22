package op.creditmanager.client.search;

import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.FormatUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class PaylogSearchText {
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter GERMAN_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");

    private PaylogSearchText() { }

    public static String build(TransactionEntry entry) {
        if (entry == null) return "";
        ZonedDateTime time = Instant.ofEpochMilli(entry.getTimestamp()).atZone(ZoneId.systemDefault());
        String source = safe(entry.getSource());
        String sourceWords = source.equalsIgnoreCase("MANUAL") ? "manual manuell" : source.equalsIgnoreCase("DETECTED") ? "detected erkannt" : source;
        String wholeAmount = Math.abs(entry.getAmount() - Math.rint(entry.getAmount())) < 0.0000001D
                ? Long.toString((long) entry.getAmount()) : "";
        String text = String.join(" ", safe(entry.getFromPlayer()), safe(entry.getToPlayer()), wholeAmount,
                String.valueOf(entry.getAmount()), String.format(Locale.ROOT, "%.2f", entry.getAmount()),
                FormatUtil.formatAmount(entry.getAmount()), FormatUtil.formatChartAmount(entry.getAmount(), false),
                safe(entry.getRawText()), safe(entry.getMetadata()), sourceWords,
                time.toLocalDate().toString(), GERMAN_DATE.format(time), GERMAN_DATE_TIME.format(time), String.valueOf(entry.getTimestamp()));
        return SearchNormalizer.normalize(text);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
