package op.creditmanager.client.gui.modern.query;

public final class ModernQueryDebouncer {
    private final long delayMs;
    private String rawKey = "";
    private String committedKey = "";
    private long changedAt;

    public ModernQueryDebouncer(long delayMs) { this.delayMs = delayMs; }

    public boolean update(String value, long now) {
        String next = value == null ? "" : value;
        if (next.equals(rawKey)) return false;
        rawKey = next;
        changedAt = now;
        return true;
    }

    public boolean ready(long now) {
        if (rawKey.equals(committedKey) || now - changedAt < delayMs) return false;
        committedKey = rawKey;
        return true;
    }

    public void commitImmediately(String value) {
        rawKey = value == null ? "" : value;
        committedKey = rawKey;
        changedAt = 0L;
    }

    public String committedKey() { return committedKey; }
}
