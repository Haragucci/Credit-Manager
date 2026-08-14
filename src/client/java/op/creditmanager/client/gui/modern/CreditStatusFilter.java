package op.creditmanager.client.gui.modern;

import op.creditmanager.client.model.CreditEntry;

enum CreditStatusFilter {
    ACTIVE("Aktiv"),
    OPEN("Offen"),
    PARTIAL("Teilweise"),
    PAID("Bezahlt"),
    CLOSED("Abgeschlossen"),
    CANCELLED("Storniert"),
    ALL("Alle");

    private final String label;

    CreditStatusFilter(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    boolean matches(CreditEntry entry) {
        if (entry == null) return false;
        return switch (this) {
            case ACTIVE -> !entry.isArchived() && ("OPEN".equals(entry.getStatus()) || "PARTIAL".equals(entry.getStatus()));
            case OPEN -> "OPEN".equals(entry.getStatus());
            case PARTIAL -> "PARTIAL".equals(entry.getStatus());
            case PAID -> "PAID".equals(entry.getStatus());
            case CLOSED -> "CLOSED".equals(entry.getStatus());
            case CANCELLED -> "CANCELLED".equals(entry.getStatus());
            case ALL -> true;
        };
    }
}
