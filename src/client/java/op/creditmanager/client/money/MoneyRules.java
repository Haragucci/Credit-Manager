package op.creditmanager.client.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalDouble;

public final class MoneyRules {
    public static final double EPSILON = 0.0001D;
    public static final double MAX_AMOUNT = 1_000_000_000_000_000D;

    private MoneyRules() { }

    public static double normalize(double value) {
        if (!Double.isFinite(value)) return Double.NaN;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static boolean isPositive(double value) {
        return Double.isFinite(value) && value > 0D && value <= MAX_AMOUNT;
    }

    public static double minRemaining(double requested, double remaining) {
        if (!isPositive(requested) || !Double.isFinite(remaining) || remaining <= EPSILON) return 0D;
        return normalize(Math.min(requested, remaining));
    }

    public static OptionalDouble parse(String raw) {
        if (raw == null || raw.isBlank()) return OptionalDouble.empty();
        try {
            double value = normalize(Double.parseDouble(normalizeNumber(raw)));
            return isPositive(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (IllegalArgumentException exception) {
            return OptionalDouble.empty();
        }
    }

    private static String normalizeNumber(String raw) {
        String compact = raw.trim().replace(" ", "");
        if (compact.isBlank()) throw new IllegalArgumentException();
        int lastComma = compact.lastIndexOf(',');
        int lastDot = compact.lastIndexOf('.');
        if (lastComma < 0 && lastDot < 0) {
            requireDigits(compact);
            return compact;
        }
        if (lastComma >= 0 && lastDot >= 0) {
            char decimalSeparator = lastComma > lastDot ? ',' : '.';
            char groupingSeparator = decimalSeparator == ',' ? '.' : ',';
            int decimalIndex = Math.max(lastComma, lastDot);
            String integer = compact.substring(0, decimalIndex);
            String fraction = compact.substring(decimalIndex + 1);
            if (fraction.isEmpty() || fraction.length() > 2 || containsSeparator(fraction)) throw new IllegalArgumentException();
            return normalizeInteger(integer, groupingSeparator) + "." + fraction;
        }
        char separator = lastComma >= 0 ? ',' : '.';
        return normalizeSingleSeparatorNumber(compact, separator);
    }

    private static String normalizeSingleSeparatorNumber(String value, char separator) {
        String[] groups = value.split("\\" + separator, -1);
        if (groups.length == 1) {
            requireDigits(value);
            return value;
        }
        String last = groups[groups.length - 1];
        if (last.isEmpty()) throw new IllegalArgumentException();
        if (groups.length == 2) {
            requireDigits(groups[0]);
            requireDigits(last);
            if (last.length() <= 2) return groups[0] + "." + last;
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

    private static String normalizeInteger(String value, char groupingSeparator) {
        if (value.indexOf(groupingSeparator) < 0) {
            requireDigits(value);
            return value;
        }
        char otherSeparator = groupingSeparator == ',' ? '.' : ',';
        if (value.indexOf(otherSeparator) >= 0) throw new IllegalArgumentException();
        return joinGroups(value.split("\\" + groupingSeparator, -1));
    }

    private static String joinGroups(String[] groups) {
        if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3) throw new IllegalArgumentException();
        requireDigits(groups[0]);
        StringBuilder result = new StringBuilder(groups[0]);
        for (int index = 1; index < groups.length; index++) {
            String group = groups[index];
            requireDigits(group);
            if (group.length() == 3) {
                result.append(group);
            } else {
                throw new IllegalArgumentException();
            }
        }
        return result.toString();
    }

    private static void requireDigits(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException();
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) throw new IllegalArgumentException();
        }
    }

    private static boolean containsSeparator(String value) {
        return value.indexOf('.') >= 0 || value.indexOf(',') >= 0;
    }

}
