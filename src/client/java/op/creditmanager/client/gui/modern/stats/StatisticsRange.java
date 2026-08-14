package op.creditmanager.client.gui.modern.stats;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public record StatisticsRange(boolean valid, long fromInclusive, long toInclusive, LocalDate start, LocalDate end,
                              String error) {
    public static StatisticsRange preset(long fromInclusive, long toInclusive) {
        return new StatisticsRange(true, fromInclusive, toInclusive, null, null, "");
    }

    public static StatisticsRange custom(String startText, String endText, ZoneId zone) {
        String normalizedStart = startText == null ? "" : startText.trim();
        String normalizedEnd = endText == null ? "" : endText.trim();
        if (normalizedStart.isEmpty() || normalizedEnd.isEmpty()) {
            return invalid("Bitte Start- und Enddatum im Format YYYY-MM-DD eingeben.");
        }
        try {
            LocalDate start = LocalDate.parse(normalizedStart);
            LocalDate end = LocalDate.parse(normalizedEnd);
            if (end.isBefore(start)) return invalid("Das Enddatum darf nicht vor dem Startdatum liegen.");
            long from = start.atStartOfDay(zone).toInstant().toEpochMilli();
            long to = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L;
            return new StatisticsRange(true, from, to, start, end, "");
        } catch (DateTimeParseException | ArithmeticException exception) {
            return invalid("Ungültiges Datum. Erwartet wird YYYY-MM-DD.");
        }
    }

    private static StatisticsRange invalid(String error) {
        return new StatisticsRange(false, 0L, 0L, null, null, error);
    }
}
