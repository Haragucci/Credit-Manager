package op.creditmanager.client.gui.modern.toast;

final class ModernToast {
    final String message;
    final ModernToastType type;
    final long createdAt = System.currentTimeMillis();
    boolean dismissed;

    ModernToast(String message, ModernToastType type) {
        this.message = message == null ? "" : message;
        this.type = type == null ? ModernToastType.INFO : type;
    }

    float visibility(long now) {
        long age = now - createdAt;
        if (dismissed) return 0.0F;
        if (age < 180) return age / 180.0F;
        if (age > 4_000) return Math.max(0.0F, 1.0F - (age - 4_000) / 260.0F);
        return 1.0F;
    }
}
