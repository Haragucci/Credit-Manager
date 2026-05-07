package op.creditmanager.client.util;

import op.creditmanager.client.model.CreditEntry;

import java.text.NumberFormat;
import java.util.Locale;

public class FormatUtil {

    private static final NumberFormat BETRAG_FORMAT = NumberFormat.getNumberInstance(Locale.GERMAN);

    static {
        BETRAG_FORMAT.setMinimumFractionDigits(2);
        BETRAG_FORMAT.setMaximumFractionDigits(2);
    }

    public static String formatAmount(double betrag) {
        return BETRAG_FORMAT.format(betrag) + "$";
    }

    @Deprecated public static String formatiereBetrag(double betrag) { return formatAmount(betrag); }

    public static String formatAmountColored(double betrag) {
        return "§6" + formatAmount(betrag) + "§r";
    }

    @Deprecated public static String formatiereBetragFarbig(double betrag) { return formatAmountColored(betrag); }

    public static String getStatusDisplay(String status) {
        return switch (status) {
            case "OPEN"      -> "§4§lOFFEN";
            case "PARTIAL"   -> "§e§lZAHLUNG LÄUFT";
            case "PAID"      -> "§a§lBEZAHLT";
            case "CANCELLED" -> "§8§lSTORNIERT";
            default          -> "§7" + status;
        };
    }

    @Deprecated public static String getStatusAnzeige(String status) { return getStatusDisplay(status); }

    public static String shortId(CreditEntry eintrag) {
        return eintrag.getId().toString().substring(0, 8);
    }

    @Deprecated public static String kurzId(CreditEntry eintrag) { return shortId(eintrag); }

    public static String formatCreditSummary(CreditEntry e) {
        String name = e.getDealName() != null ? e.getDealName() : shortId(e);

        StringBuilder sb = new StringBuilder();

        sb.append("§7[§b").append(name).append("§7]\n");

        sb.append("§7Von → An: §f")
                .append(e.getDebtor())
                .append(" §7→ §f")
                .append(e.getCreditor())
                .append("\n");

        sb.append("§7Betrag: ")
                .append(formatAmountColored(e.getAmount()))
                .append("\n");

        sb.append("§7Offen: §c")
                .append(formatAmount(e.getRemainingAmount()))
                .append("\n");

        sb.append("§7Status: ")
                .append(getStatusDisplay(e.getStatus()));

        return sb.toString();
    }

    public static double parseMoney(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Leerer Betrag");
        }

        String s = input.trim().toLowerCase().replace(" ", "");

        double multiplier = 1;

        if (s.endsWith("mrd")) {
            multiplier = 1_000_000_000;
            s = s.substring(0, s.length() - 3);
        } else if (s.endsWith("mio")) {
            multiplier = 1_000_000;
            s = s.substring(0, s.length() - 3);
        } else if (s.endsWith("kk")) {
            multiplier = 1_000_000;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("k")) {
            multiplier = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            multiplier = 1_000_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("b")) {
            multiplier = 1_000_000_000;
            s = s.substring(0, s.length() - 1);
        }

        s = s.replace(".", "").replace(",", ".");

        double value = Double.parseDouble(s);

        if (value <= 0) {
            throw new IllegalArgumentException("Betrag muss > 0 sein");
        }

        return value * multiplier;
    }

    @Deprecated public static String formatiereDealZeile(CreditEntry e) { return formatCreditSummary(e); }
}