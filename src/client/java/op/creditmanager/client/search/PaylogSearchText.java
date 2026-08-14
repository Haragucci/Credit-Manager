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
        long amountMinor = entry.getAmountMinor();
        String wholeAmount = amountMinor % 100L == 0L ? Long.toString(amountMinor / 100L) : "";
        String exactAmount = op.creditmanager.client.money.MoneyRules.toMajor(amountMinor).toPlainString();
        String text = String.join(" ", safe(entry.getFromPlayer()), safe(entry.getToPlayer()), wholeAmount,
                exactAmount, exactAmount.replace('.', ','),
                FormatUtil.formatAmountMinor(amountMinor), FormatUtil.formatChartAmountMinor(amountMinor, false),
                safe(entry.getRawText()), safe(entry.getMetadata()), sourceWords,
                time.toLocalDate().toString(), GERMAN_DATE.format(time), GERMAN_DATE_TIME.format(time), String.valueOf(entry.getTimestamp()));
        return SearchNormalizer.normalize(text);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
