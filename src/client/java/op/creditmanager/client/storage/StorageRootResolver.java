package op.creditmanager.client.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class StorageRootResolver {
    public StorageLocation resolve(Path gameDirectory, Map<String, String> properties) {
        Path gameDir = normalize(gameDirectory);
        Map<String, String> values = properties == null ? Map.of() : properties;
        Path addonsRoot = parentOfNamedDirectory(values.get("net.labymod.addons-dir"), "addons", 1);
        Path fabricRoot = fabricRoot(values.get("net.labymod.fabric-dir"));
        Path overlayRoot = overlayRoot(gameDir);
        boolean launcherSignal = present(values, "net.labymod.launcher") || present(values, "net.labymod.mod-pack-id")
                || present(values, "net.labymod.addons-dir") || present(values, "net.labymod.fabric-dir");
        boolean structuredOverlaySignal = overlayRoot != null && overlayRoot.getParent() != null
                && named(overlayRoot.getParent(), "instances");
        if (!launcherSignal && !structuredOverlaySignal) {
            Path canonical = gameDir.resolve("CreditManagerLogs");
            return new StorageLocation(canonical, null, StorageEnvironment.STANDARD,
                    gameDir.toString(), ResolutionSource.STANDARD_GAME_DIR, true, List.of("gameDir"));
        }

        Set<Path> candidates = new LinkedHashSet<>();
        if (addonsRoot != null) candidates.add(addonsRoot);
        if (fabricRoot != null) candidates.add(fabricRoot);
        if (overlayRoot != null) candidates.add(overlayRoot);
        List<String> signals = new ArrayList<>();
        if (addonsRoot != null) signals.add("addons-dir");
        if (fabricRoot != null) signals.add("fabric-dir");
        if (overlayRoot != null) signals.add("overlay-gameDir");
        if (present(values, "net.labymod.launcher")) signals.add("launcher");
        if (present(values, "net.labymod.mod-pack-id")) signals.add("mod-pack-id");
        if (candidates.size() != 1) {
            return new StorageLocation(gameDir.resolve("CreditManagerLogs"), overlayRoot == null ? null : gameDir.resolve("CreditManagerLogs"),
                    StorageEnvironment.LABYMOD, instanceKey(null, values), ResolutionSource.AMBIGUOUS, false, List.copyOf(signals));
        }
        Path instanceRoot = candidates.iterator().next();
        if (!gameDir.startsWith(instanceRoot) || instanceRoot.getFileName() == null) {
            return new StorageLocation(gameDir.resolve("CreditManagerLogs"), overlayRoot == null ? null : gameDir.resolve("CreditManagerLogs"),
                    StorageEnvironment.LABYMOD, instanceKey(instanceRoot, values), ResolutionSource.AMBIGUOUS, false, List.copyOf(signals));
        }
        ResolutionSource source = signals.stream().filter(signal -> signal.endsWith("dir") || signal.equals("overlay-gameDir")).count() > 1
                ? ResolutionSource.CROSS_VALIDATED
                : addonsRoot != null ? ResolutionSource.ADDONS_DIR
                : fabricRoot != null ? ResolutionSource.FABRIC_DIR : ResolutionSource.OVERLAY_GAME_DIR;
        Path legacy = instanceRoot.resolve("overlay").resolve("CreditManagerLogs");
        return new StorageLocation(instanceRoot.resolve("CreditManagerLogs"), legacy, StorageEnvironment.LABYMOD,
                instanceKey(instanceRoot, values), source, true, List.copyOf(signals));
    }

    public StorageLocation resolve(Path gameDirectory) {
        return resolve(gameDirectory, systemProperties());
    }

    private Map<String, String> systemProperties() {
        return Map.of(
                "net.labymod.launcher", System.getProperty("net.labymod.launcher", ""),
                "net.labymod.mod-pack-id", System.getProperty("net.labymod.mod-pack-id", ""),
                "net.labymod.addons-dir", System.getProperty("net.labymod.addons-dir", ""),
                "net.labymod.fabric-dir", System.getProperty("net.labymod.fabric-dir", "")
        );
    }

    private Path parentOfNamedDirectory(String raw, String name, int levels) {
        if (raw == null || raw.isBlank()) return null;
        Path path = normalize(Path.of(raw));
        if (!named(path, name)) return null;
        for (int index = 0; index < levels && path != null; index++) path = path.getParent();
        return path;
    }

    private Path fabricRoot(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Path path = normalize(Path.of(raw));
        if (!named(path, "fabric") || path.getParent() == null || !named(path.getParent(), "loader")) return null;
        return path.getParent().getParent();
    }

    private Path overlayRoot(Path gameDir) {
        return named(gameDir, "overlay") ? gameDir.getParent() : null;
    }

    private boolean named(Path path, String expected) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).equals(expected.toLowerCase(Locale.ROOT));
    }

    private boolean present(Map<String, String> values, String key) {
        String value = values.get(key);
        return value != null && !value.isBlank();
    }

    private String instanceKey(Path instanceRoot, Map<String, String> properties) {
        String modPackId = properties.get("net.labymod.mod-pack-id");
        String rootName = instanceRoot == null || instanceRoot.getFileName() == null ? "unresolved" : instanceRoot.getFileName().toString();
        return modPackId == null || modPackId.isBlank() ? rootName : rootName + ':' + modPackId.trim();
    }

    private Path normalize(Path path) {
        if (path == null) throw new IllegalArgumentException("gameDirectory");
        return path.toAbsolutePath().normalize();
    }

    public enum StorageEnvironment { STANDARD, LABYMOD }
    public enum ResolutionSource { STANDARD_GAME_DIR, ADDONS_DIR, FABRIC_DIR, OVERLAY_GAME_DIR, CROSS_VALIDATED, AMBIGUOUS }
    public record StorageLocation(Path canonicalRoot, Path legacyRoot, StorageEnvironment environment, String instanceKey,
                                  ResolutionSource source, boolean resolved, List<String> signals) { }
}
