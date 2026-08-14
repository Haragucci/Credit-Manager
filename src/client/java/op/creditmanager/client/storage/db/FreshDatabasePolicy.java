package op.creditmanager.client.storage.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class FreshDatabasePolicy {
    private static final Set<String> LEGACY_JSON = Set.of(
            "credits.json",
            "credit_state.json",
            "players.json",
            "payments.json",
            "transactions.json",
            "credit_events.json"
    );

    Decision inspect(Path canonicalRoot, Path legacyRoot, Path mirrorRoot, boolean leaseHeld) throws IOException {
        if (!leaseHeld) return new Decision(BootstrapMode.REJECTED, List.of("process lease missing"));
        List<String> evidence = new ArrayList<>();
        boolean legacyJsonOnly = inspectRoot(canonicalRoot, evidence);
        if (legacyRoot != null && !legacyRoot.toAbsolutePath().normalize().equals(canonicalRoot.toAbsolutePath().normalize())) {
            legacyJsonOnly &= inspectRoot(legacyRoot, evidence);
        }
        inspectMirror(mirrorRoot, evidence);
        if (evidence.isEmpty()) return new Decision(BootstrapMode.FRESH, List.of());
        if (legacyJsonOnly && evidence.stream().allMatch(value -> value.startsWith("legacy-json:"))) {
            return new Decision(BootstrapMode.LEGACY_JSON_IMPORT, List.copyOf(evidence));
        }
        return new Decision(BootstrapMode.REJECTED, List.copyOf(evidence));
    }

    private void inspectMirror(Path root, List<String> evidence) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root, 2)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".tmp")) continue;
                evidence.add("mirror:" + root.relativize(path));
            }
        }
    }

    private boolean inspectRoot(Path root, List<String> evidence) throws IOException {
        if (root == null || !Files.exists(root)) return true;
        boolean legacyJsonOnly = true;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals(".creditmanager.lock") || name.equals("client_config.json") || name.endsWith(".tmp")) continue;
                if (LEGACY_JSON.contains(name)) {
                    evidence.add("legacy-json:" + name);
                    continue;
                }
                if (name.matches("payments_backup_[0-9]+\\.json")) {
                    evidence.add("legacy-json:" + name);
                    continue;
                }
                evidence.add("prior-data:" + root.relativize(path));
                legacyJsonOnly = false;
            }
        }
        return legacyJsonOnly;
    }

    enum BootstrapMode { FRESH, LEGACY_JSON_IMPORT, REJECTED }
    record Decision(BootstrapMode mode, List<String> evidence) {
        boolean allowsBootstrap() { return mode != BootstrapMode.REJECTED; }
    }
}
