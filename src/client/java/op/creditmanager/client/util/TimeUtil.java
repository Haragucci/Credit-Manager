package op.creditmanager.client.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class TimeUtil {

    private static final DateTimeFormatter DATUM_FORMAT      = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATUM_ZEIT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static String formatDate(long zeitstempelMs) {
        return Instant.ofEpochMilli(zeitstempelMs)
                .atZone(ZoneId.systemDefault())
                .format(DATUM_FORMAT);
    }

    @Deprecated public static String formatDatum(long ms) { return formatDate(ms); }

    public static String formatDateTime(long zeitstempelMs) {
        return Instant.ofEpochMilli(zeitstempelMs)
                .atZone(ZoneId.systemDefault())
                .format(DATUM_ZEIT_FORMAT);
    }

    @Deprecated public static String formatDatumZeit(long ms) { return formatDateTime(ms); }

    public static Long parseDueDate(String eingabe) {
        if (eingabe == null || eingabe.isBlank()) return null;
        try {
            LocalDate datum = LocalDate.parse(eingabe, DATUM_FORMAT);
            return datum.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static boolean isOverdue(Long fälligkeitMs) {
        if (fälligkeitMs == null) return false;
        return System.currentTimeMillis() > fälligkeitMs;
    }

    @Deprecated public static boolean istÜberfällig(Long ms) { return isOverdue(ms); }

    public static String getDueDateDisplay(Long fälligkeitMs) {
        if (fälligkeitMs == null) return "§7Kein Fälligkeitsdatum";
        String formatiert = formatDate(fälligkeitMs);
        if (isOverdue(fälligkeitMs)) {
            return "§c" + formatiert + " §c§l(ÜBERFÄLLIG!)";
        }
        return "§f" + formatiert;
    }
}