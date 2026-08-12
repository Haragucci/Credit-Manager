package op.creditmanager.client.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class MoneyRules {
    public static final int SCALE = 2;
    public static final long MINOR_FACTOR = 100L;
    public static final long MAX_MINOR = 100_000_000_000_000_000L;
    public static final BigDecimal MAX_MAJOR = BigDecimal.valueOf(MAX_MINOR, SCALE);
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final Pattern UNSUPPORTED = Pattern.compile("[^0-9.,]");

    private MoneyRules() { }

    public static Optional<MoneyAmount> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            String compact = raw.trim().toLowerCase(Locale.ROOT)
                    .replace(" ", "")
                    .replace("_", "")
                    .replace("$", "")
                    .replace("€", "");
            Suffix suffix = stripSuffix(compact);
            if (suffix.number().isBlank() || UNSUPPORTED.matcher(suffix.number()).find()) return Optional.empty();
            String normalized = normalizeNumber(suffix.number(), suffix.multiplier().compareTo(BigDecimal.ONE) != 0);
            BigDecimal major = new BigDecimal(normalized).multiply(suffix.multiplier());
            return Optional.of(fromMajor(major, true));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static MoneyAmount fromMajor(BigDecimal major, boolean positiveRequired) {
        if (major == null) throw new IllegalArgumentException("Betrag fehlt");
        BigDecimal rounded = major.setScale(SCALE, ROUNDING);
        long minor = rounded.movePointRight(SCALE).longValueExact();
        if (positiveRequired && minor <= 0L) throw new IllegalArgumentException("Betrag muss positiv sein");
        if (Math.abs(minor) > MAX_MINOR) throw new IllegalArgumentException("Betrag liegt außerhalb des erlaubten Bereichs");
        return MoneyAmount.ofMinor(minor);
    }

    public static MoneyAmount fromLegacyDouble(double value, boolean positiveRequired) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nicht endlicher Legacy-Betrag");
        return fromMajor(BigDecimal.valueOf(value), positiveRequired);
    }

    public static long minPositive(long requestedMinor, long remainingMinor) {
        if (!isPositive(requestedMinor) || remainingMinor <= 0L) return 0L;
        return Math.min(requestedMinor, remainingMinor);
    }

    public static boolean isPositive(long minorUnits) {
        return minorUnits > 0L && minorUnits <= MAX_MINOR;
    }

    public static boolean isValid(long minorUnits) {
        return minorUnits >= -MAX_MINOR && minorUnits <= MAX_MINOR;
    }

    public static BigDecimal toMajor(long minorUnits) {
        if (!isValid(minorUnits)) throw new IllegalArgumentException("Betrag liegt außerhalb des erlaubten Bereichs");
        return BigDecimal.valueOf(minorUnits, SCALE);
    }

    public static double toDisplayDouble(long minorUnits) {
        return toMajor(minorUnits).doubleValue();
    }

    public static String toPlainMajor(long minorUnits) {
        return toMajor(minorUnits).setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static Suffix stripSuffix(String value) {
        for (Suffix suffix : Suffix.values()) {
            if (!suffix.text().isEmpty() && value.endsWith(suffix.text())) {
                return new Suffix(value.substring(0, value.length() - suffix.text().length()), suffix.multiplier());
            }
        }
        return new Suffix(value, BigDecimal.ONE);
    }

    private static String normalizeNumber(String value, boolean suffixPresent) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        int lastComma = value.lastIndexOf(',');
        int lastDot = value.lastIndexOf('.');
        if (lastComma < 0 && lastDot < 0) {
            requireDigits(value);
            return value;
        }
        if (lastComma >= 0 && lastDot >= 0) {
            char decimalSeparator = lastComma > lastDot ? ',' : '.';
            char groupingSeparator = decimalSeparator == ',' ? '.' : ',';
            int decimalIndex = Math.max(lastComma, lastDot);
            String integer = normalizeGroupedInteger(value.substring(0, decimalIndex), groupingSeparator);
            String fraction = value.substring(decimalIndex + 1);
            requireDigits(fraction);
            if (fraction.length() > 2 && !suffixPresent) throw new IllegalArgumentException();
            return integer + "." + fraction;
        }
        char separator = lastComma >= 0 ? ',' : '.';
        return normalizeSingleSeparatorNumber(value, separator, suffixPresent);
    }

    private static String normalizeSingleSeparatorNumber(String value, char separator, boolean suffixPresent) {
        String[] groups = value.split(Pattern.quote(String.valueOf(separator)), -1);
        if (groups.length == 1) {
            requireDigits(value);
            return value;
        }
        if (suffixPresent) {
            String last = groups[groups.length - 1];
            requireDigits(last);
            if (groups.length == 2) {
                requireDigits(groups[0]);
                return groups[0] + "." + last;
            }
            String[] integerGroups = new String[groups.length - 1];
            System.arraycopy(groups, 0, integerGroups, 0, integerGroups.length);
            return joinGroups(integerGroups) + "." + last;
        }
        String last = groups[groups.length - 1];
        requireDigits(last);
        if (groups.length == 2) {
            requireDigits(groups[0]);
            if (last.length() <= 2) return groups[0] + "." + last;
            if (groups[0].equals("0")) return groups[0] + "." + last;
            if (last.length() == 3) return groups[0] + last;
            throw new IllegalArgumentException();
        }
        if (last.length() <= 2) {
            String[] integerGroups = new String[groups.length - 1];
            System.arraycopy(groups, 0, integerGroups, 0, integerGroups.length);
            return joinGroups(integerGroups) + "." + last;
        }
        return joinGroups(groups);
    }

    private static String normalizeGroupedInteger(String value, char groupingSeparator) {
        if (value.indexOf(groupingSeparator) < 0) {
            requireDigits(value);
            return value;
        }
        return joinGroups(value.split(Pattern.quote(String.valueOf(groupingSeparator)), -1));
    }

    private static String joinGroups(String[] groups) {
        if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3) throw new IllegalArgumentException();
        requireDigits(groups[0]);
        StringBuilder result = new StringBuilder(groups[0]);
        for (int index = 1; index < groups.length; index++) {
            String group = groups[index];
            requireDigits(group);
            if (group.length() != 3) throw new IllegalArgumentException();
            result.append(group);
        }
        return result.toString();
    }

    private static void requireDigits(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException();
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) throw new IllegalArgumentException();
        }
    }

    private record Suffix(String number, BigDecimal multiplier) {
        private static final Suffix MRD = new Suffix("mrd", new BigDecimal("1000000000"));
        private static final Suffix MIO = new Suffix("mio", new BigDecimal("1000000"));
        private static final Suffix KK = new Suffix("kk", new BigDecimal("1000000"));
        private static final Suffix K = new Suffix("k", new BigDecimal("1000"));
        private static final Suffix M = new Suffix("m", new BigDecimal("1000000"));
        private static final Suffix B = new Suffix("b", new BigDecimal("1000000000"));

        private static Suffix[] values() {
            return new Suffix[]{MRD, MIO, KK, K, M, B};
        }

        private String text() {
            if (this == MRD) return "mrd";
            if (this == MIO) return "mio";
            if (this == KK) return "kk";
            if (this == K) return "k";
            if (this == M) return "m";
            if (this == B) return "b";
            return "";
        }
    }
}
