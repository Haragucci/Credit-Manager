package op.creditmanager.client.search;

import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.FormatUtil;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class PaylogSearch {
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter GERMAN_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private PaylogSearch() {
    }

    public static boolean matches(TransactionEntry entry, String query) {
        if (query == null || query.isBlank()) return true;
        for (String token : query.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            if (isDateToken(token)) {
                if (!dateMatches(entry.getTimestamp(), token)) return false;
            } else if (isAmountFilter(token)) {
                if (!amountMatches(entry.getAmount(), token)) return false;
            } else if (!textMatches(entry, token)) {
                return false;
            }
        }
        return true;
    }

    public static int score(TransactionEntry entry, String query) {
        if (query == null || query.isBlank()) return 1;
        int score = 0;
        for (String token : query.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            if (isDateToken(token) || isAmountFilter(token)) {
                score += 1_000;
                continue;
            }
            score += textScore(entry, token);
        }
        return Math.max(1, score);
    }

    private static boolean isAmountFilter(String token) {
        return token.matches("[<>]=?[0-9][0-9.,]*") || token.matches("[0-9][0-9.,]*-[0-9][0-9.,]*");
    }

    private static boolean amountMatches(double amount, String token) {
        try {
            if (token.startsWith(">=")) return amount >= FormatUtil.parseMoney(token.substring(2));
            if (token.startsWith("<=")) return amount <= FormatUtil.parseMoney(token.substring(2));
            if (token.startsWith(">")) return amount > FormatUtil.parseMoney(token.substring(1));
            if (token.startsWith("<")) return amount < FormatUtil.parseMoney(token.substring(1));
            int dash = token.indexOf('-');
            double low = FormatUtil.parseMoney(token.substring(0, dash));
            double high = FormatUtil.parseMoney(token.substring(dash + 1));
            return amount >= Math.min(low, high) && amount <= Math.max(low, high);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean textMatches(TransactionEntry entry, String token) {
        return textScore(entry, token) > 0;
    }

    private static int textScore(TransactionEntry entry, String token) {
        String wanted = SearchNormalizer.normalize(token);
        if (wanted.isEmpty()) return 1;
        String visible = SearchNormalizer.normalize(visibleText(entry));
        if (visible.equals(wanted)) return 950;
        if (visible.startsWith(wanted)) return 850;
        if (visible.contains(wanted)) return 700;
        return Math.max(FuzzySearch.score(entry.getFromPlayer(), token), FuzzySearch.score(entry.getToPlayer(), token));
    }

    private static String visibleText(TransactionEntry entry) {
        ZonedDateTime time = Instant.ofEpochMilli(entry.getTimestamp()).atZone(ZoneId.systemDefault());
        String amount = FormatUtil.formatAmount(entry.getAmount());
        return String.join(" ",
                safe(entry.getFromPlayer()), safe(entry.getToPlayer()), amount,
                String.format(Locale.ROOT, "%.2f", entry.getAmount()),
                safe(entry.getRawText()), safe(entry.getNormalizedText()), safe(entry.getHash()), safe(entry.getMetadata()),
                GERMAN_DATE.format(time), GERMAN_DATE_TIME.format(time), ISO_DATE_TIME.format(time),
                String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute()));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isDateToken(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return value.equals("heute") || value.equals("gestern") || token.matches("\\d{4}-\\d{2}-\\d{2}") || token.matches("\\d{2}\\.\\d{2}\\.\\d{4}");
    }

    private static boolean dateMatches(long timestamp, String token) {
        LocalDate wanted;
        String value = token.toLowerCase(Locale.ROOT);
        if (value.equals("heute")) wanted = LocalDate.now();
        else if (value.equals("gestern")) wanted = LocalDate.now().minusDays(1);
        else {
            try {
                wanted = token.contains("-") ? LocalDate.parse(token) : LocalDate.parse(token, GERMAN_DATE);
            } catch (DateTimeParseException ignored) {
                return false;
            }
        }
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().equals(wanted);
    }
}
