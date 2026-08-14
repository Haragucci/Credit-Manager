package op.creditmanager.client.money;

import op.creditmanager.client.model.CreditEntry;

public final class CreditStatusRules {
    private CreditStatusRules() { }

    public static String derive(long amountMinor, long paidMinor) {
        if (!MoneyRules.isPositive(amountMinor)) throw new IllegalArgumentException("Ungültiger Gesamtbetrag");
        if (paidMinor < 0L || paidMinor > amountMinor) throw new IllegalArgumentException("Ungültiger bezahlter Betrag");
        if (paidMinor == 0L) return "OPEN";
        if (paidMinor == amountMinor) return "PAID";
        return "PARTIAL";
    }

    public static boolean isManualFinal(String status) {
        return "CLOSED".equals(status) || "CANCELLED".equals(status);
    }

    public static boolean isActive(CreditEntry entry) {
        return entry != null && !entry.isArchived() && ("OPEN".equals(entry.getStatus()) || "PARTIAL".equals(entry.getStatus()));
    }
}
