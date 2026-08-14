package op.creditmanager.client.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class BackupMirrorPathResolver {
    public Resolution resolve(Path primaryRoot, StorageRootResolver.StorageLocation location) {
        String appData = System.getenv("APPDATA");
        String userHome = System.getProperty("user.home", "");
        return resolve(primaryRoot, location, appData, userHome, null);
    }

    public Resolution resolve(Path primaryRoot, StorageRootResolver.StorageLocation location,
                              String appData, String userHome, Path customBase) {
        if (primaryRoot == null || location == null || !location.resolved()) {
            return Resolution.disabled("primary storage is unresolved");
        }
        Path base;
        try {
            if (customBase != null) {
                base = customBase.toAbsolutePath().normalize();
            } else if (appData != null && !appData.isBlank()) {
                base = Path.of(appData).toAbsolutePath().normalize().resolve("CreditManager").resolve("BackupMirror");
            } else if (userHome != null && !userHome.isBlank()) {
                base = Path.of(userHome).toAbsolutePath().normalize().resolve(".creditmanager").resolve("BackupMirror");
            } else {
                return Resolution.disabled("no stable user data directory is available");
            }
        } catch (RuntimeException exception) {
            return Resolution.disabled("mirror base path is invalid");
        }
        Path normalizedPrimary = primaryRoot.toAbsolutePath().normalize();
        String keyMaterial = location.instanceKey() + '|' + normalizedPrimary;
        Path candidate = base.resolve(hash(keyMaterial)).toAbsolutePath().normalize();
        List<Path> forbidden = new ArrayList<>();
        forbidden.add(normalizedPrimary);
        if (normalizedPrimary.getParent() != null) forbidden.add(normalizedPrimary.getParent());
        if (location.legacyRoot() != null) {
            Path legacy = location.legacyRoot().toAbsolutePath().normalize();
            forbidden.add(legacy);
            if (legacy.getParent() != null) forbidden.add(legacy.getParent());
        }
        try {
            Path physicalCandidate = physicalPath(candidate);
            for (Path path : forbidden) {
                Path physicalForbidden = physicalPath(path);
                if (physicalCandidate.equals(physicalForbidden) || physicalCandidate.startsWith(physicalForbidden)
                        || physicalForbidden.startsWith(physicalCandidate)) {
                    return Resolution.disabled("mirror path overlaps primary, instance, or overlay storage");
                }
            }
            return new Resolution(physicalCandidate, true, "");
        } catch (IOException exception) {
            return Resolution.disabled("mirror path could not be canonicalized safely");
        }
    }

    private Path physicalPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("path has no existing ancestor");
        Path real = existing.toRealPath();
        Path relative = existing.relativize(absolute);
        return real.resolve(relative).normalize();
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Resolution(Path root, boolean enabled, String reason) {
        private static Resolution disabled(String reason) {
            return new Resolution(null, false, reason);
        }
    }
}
