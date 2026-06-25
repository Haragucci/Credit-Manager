package op.creditmanager.client.storage;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DataHealth {
    private static String pendingWarning;
    private static final Set<String> reasons = new LinkedHashSet<>();
    private static long lastCheckedAt = System.currentTimeMillis();

    private DataHealth() {
    }

    public static synchronized void reportRecoveryRequired(Path path, boolean backupCreated) {
        lastCheckedAt = System.currentTimeMillis();
        reasons.add(path.getFileName() + (backupCreated ? " – Backup erstellt" : " – Backup konnte nicht erstellt werden"));
        if (pendingWarning == null) {
            pendingWarning = backupCreated
                    ? "Defekte Daten erkannt. Ein Backup wurde erstellt; Änderungen sind bis zur Prüfung gesperrt."
                    : "Defekte Daten erkannt. Änderungen sind bis zur Prüfung gesperrt; bitte Logs und Backups prüfen.";
        }
    }

    public static synchronized void reportRecoveryRequired(String reason) {
        lastCheckedAt = System.currentTimeMillis();
        reasons.add(reason);
        if (pendingWarning == null) {
            pendingWarning = "Ungültige Daten erkannt. Änderungen sind bis zur Prüfung gesperrt; bitte das Backup prüfen.";
        }
    }

    public static synchronized String consumeWarning() {
        String warning = pendingWarning;
        pendingWarning = null;
        return warning;
    }

    public static synchronized List<String> reasons() { return List.copyOf(reasons); }
    public static synchronized long lastCheckedAt() { return lastCheckedAt; }
    public static synchronized void clearReasons() { reasons.clear(); pendingWarning = null; lastCheckedAt = System.currentTimeMillis(); }
}
