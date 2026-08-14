package op.creditmanager.client.util;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.money.MoneyAmount;
import op.creditmanager.client.money.MoneyRules;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

public class FormatUtil {
    private static final NumberFormat BETRAG_FORMAT = NumberFormat.getNumberInstance(Locale.GERMAN);

    static {
        BETRAG_FORMAT.setMinimumFractionDigits(2);
        BETRAG_FORMAT.setMaximumFractionDigits(2);
    }

    public static String formatAmountMinor(long minorUnits) {
        return formatAmountMinor(BigInteger.valueOf(minorUnits));
    }

    public static String formatAmountMinor(BigInteger minorUnits) {
        synchronized (BETRAG_FORMAT) {
            BigDecimal major = new BigDecimal(minorUnits == null ? BigInteger.ZERO : minorUnits, 2);
            return BETRAG_FORMAT.format(major) + "$";
        }
    }

    @Deprecated
    public static String formatAmount(double amount) {
        return formatAmountMinor(MoneyRules.fromLegacyDouble(amount, false).minorUnits());
    }

    public static String formatChartAmountMinor(long minorUnits) {
        return formatChartAmountMinor(minorUnits, false);
    }

    public static String formatChartAmountMinor(long minorUnits, boolean showPlus) {
        return formatChartAmountMinor(BigInteger.valueOf(minorUnits), showPlus);
    }

    public static String formatChartAmountMinor(BigInteger minorUnits) {
        return formatChartAmountMinor(minorUnits, false);
    }

    public static String formatChartAmountMinor(BigInteger minorUnits, boolean showPlus) {
        BigInteger value = minorUnits == null ? BigInteger.ZERO : minorUnits;
        String sign = value.signum() < 0 ? "-" : showPlus && value.signum() > 0 ? "+" : "";
        BigDecimal absolute = new BigDecimal(value.abs(), 2);
        BigDecimal divisor = BigDecimal.ONE;
        String suffix = "";
        if (absolute.compareTo(new BigDecimal("999999500")) >= 0) {
            divisor = new BigDecimal("1000000000");
            suffix = "Mrd";
        } else if (absolute.compareTo(new BigDecimal("999500")) >= 0) {
            divisor = new BigDecimal("1000000");
            suffix = "M";
        } else if (absolute.compareTo(new BigDecimal("999.5")) >= 0) {
            divisor = new BigDecimal("1000");
            suffix = "k";
        }
        BigDecimal compact = absolute.divide(divisor, 2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (compact.precision() - compact.scale() > 12) {
            int exponent = compact.precision() - compact.scale() - 1;
            BigDecimal scientific = compact.movePointLeft(exponent).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
            return sign + scientific.toPlainString().replace('.', ',') + "e" + exponent + suffix + "$";
        }
        return sign + compact.toPlainString().replace('.', ',') + suffix + "$";
    }

    public static String formatChartAmount(double amount) {
        return formatChartAmount(amount, false);
    }

    public static String formatChartAmount(double amount, boolean showPlus) {
        if (!Double.isFinite(amount)) return "0$";
        double absolute = Math.abs(amount);
        String sign = amount < 0.0D ? "-" : showPlus && amount > 0.0D ? "+" : "";
        double divisor = 1.0D;
        String suffix = "";
        if (absolute >= 999_999_500.0D) {
            divisor = 1_000_000_000.0D;
            suffix = "Mrd";
        } else if (absolute >= 999_500.0D) {
            divisor = 1_000_000.0D;
            suffix = "M";
        } else if (absolute >= 999.5D) {
            divisor = 1_000.0D;
            suffix = "k";
        }
        if (divisor == 1.0D) return sign + formatChartNumber(absolute) + "$";
        double compact = absolute / divisor;
        if (compact >= 1_000_000_000.0D) {
            return sign + String.format(Locale.ROOT, "%.2e", compact).replace('.', ',') + suffix + "$";
        }
        return sign + formatChartNumber(compact) + suffix + "$";
    }

    private static String formatChartNumber(double value) {
        if (value < 0.005D) return "0";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    public static String formatAmountColoredMinor(long minorUnits) {
        return "§6" + formatAmountMinor(minorUnits) + "§r";
    }

    @Deprecated
    public static String formatAmountColored(double amount) {
        return formatAmountColoredMinor(MoneyRules.fromLegacyDouble(amount, false).minorUnits());
    }

    public static String getStatusDisplay(String status) {
        if (status == null || status.isBlank()) return "§7UNBEKANNT";
        return switch (status) {
            case "OPEN" -> "§4§lOFFEN";
            case "PARTIAL" -> "§e§lZAHLUNG LÄUFT";
            case "PAID" -> "§a§lBEZAHLT";
            case "CLOSED" -> "§8§lABGESCHLOSSEN";
            case "CANCELLED" -> "§8§lSTORNIERT";
            default -> "§7" + status;
        };
    }

    public static String shortId(CreditEntry entry) {
        if (entry == null || entry.getId() == null) return "unknown";
        String id = entry.getId().toString();
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public static String formatCreditSummary(CreditEntry entry) {
        if (entry == null) return "§cUngültiger Eintrag";
        String name = entry.getDealName() != null && !entry.getDealName().isBlank() ? entry.getDealName() : shortId(entry);
        return "§7[§b" + name + "§7]\n"
                + "§7Schuldner → Gläubiger: §f" + safeName(entry.getDebtor()) + " §7→ §f" + safeName(entry.getCreditor()) + "\n"
                + "§7Betrag: " + formatAmountColoredMinor(entry.getAmountMinor()) + "\n"
                + "§7Offen: §c" + formatAmountMinor(entry.getRemainingAmountMinor()) + "\n"
                + "§7Status: " + getStatusDisplay(entry.getStatus());
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "Unbekannt" : name;
    }

    public static long parseMoneyMinor(String input) {
        return MoneyRules.parse(input).map(MoneyAmount::minorUnits)
                .orElseThrow(() -> new IllegalArgumentException("Ungültiger Betrag"));
    }

    @Deprecated
    public static double parseMoney(String input) {
        return MoneyRules.toDisplayDouble(parseMoneyMinor(input));
    }

    @Deprecated
    public static double parseDisplayAmount(String input) {
        return MoneyRules.toDisplayDouble(parseMoneyMinor(input));
    }
}
