package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import op.creditmanager.client.storage.db.DatabaseManager.BackupArtifactType;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BackupMirrorService {
    private static final int RECENT_RETENTION = 24;
    private static final int MAX_RETENTION = 64;
    private static final Gson GSON = new Gson();
    private static final Type MANIFEST_TYPE = new TypeToken<List<MirrorBackupEntry>>() { }.getType();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC);
    private final Path root;
    private final RecoveryFileOps fileOps;

    BackupMirrorService(Path root, RecoveryFileOps fileOps) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.fileOps = fileOps == null ? new RecoveryFileOps() : fileOps;
    }

    boolean enabled() {
        return root != null;
    }

    Path root() {
        return root;
    }

    boolean hasEvidence() {
        if (!enabled() || !Files.exists(root)) return false;
        if (Files.isRegularFile(manifestFile())) return true;
        try (var paths = Files.walk(root, 2)) {
            return paths.filter(Files::isRegularFile).anyMatch(this::isBackupFile);
        } catch (IOException exception) {
            return true;
        }
    }

    MirrorWriteResult mirror(Path source, MirrorBackupEntry entry) {
        if (!enabled()) return MirrorWriteResult.disabled();
        if (source == null || entry == null || !entry.healthy()
                || entry.artifactType() != BackupArtifactType.RESTORABLE_HEALTHY
                || !Files.isRegularFile(source)) return MirrorWriteResult.failed("mirror source is not a validated healthy backup");
        Path target = safeBackupPath(entry.filename());
        if (target == null) return MirrorWriteResult.failed("mirror filename is unsafe");
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            fileOps.createDirectories(target.getParent());
            String sourceHash = sha256(source);
            long sourceSize = Files.size(source);
            if (!sourceHash.equals(entry.sha256()) || sourceSize != entry.size()) {
                return MirrorWriteResult.failed("local backup metadata does not match the source artifact");
            }
            if (Files.isRegularFile(target)) {
                if (!sourceHash.equals(sha256(target)) || sourceSize != Files.size(target)) {
                    return MirrorWriteResult.failed("a divergent mirror artifact already uses the same filename");
                }
            } else {
                fileOps.copy(source, temporary);
                forceFile(temporary);
                if (!sourceHash.equals(sha256(temporary)) || sourceSize != Files.size(temporary)) {
                    fileOps.deleteIfExists(temporary);
                    return MirrorWriteResult.failed("mirror copy checksum verification failed");
                }
                fileOps.moveWithoutReplacing(temporary, target);
                if (!sourceHash.equals(sha256(target)) || sourceSize != Files.size(target)) {
                    fileOps.deleteIfExists(target);
                    return MirrorWriteResult.failed("published mirror checksum verification failed");
                }
            }
            ManifestRead manifest = readManifest();
            List<MirrorBackupEntry> entries = new ArrayList<>(manifest.entries());
            entries.removeIf(value -> value.filename().equals(entry.filename()));
            entries.add(entry);
            entries.sort(Comparator.comparingLong(MirrorBackupEntry::createdAt).reversed());
            writeManifest(entries);
            if (manifest.valid()) enforceRetention(entries);
            return new MirrorWriteResult(true, target, entry, "mirror backup verified");
        } catch (Exception exception) {
            try {
                fileOps.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            return MirrorWriteResult.failed(exception.getMessage());
        }
    }

    List<Path> candidateFiles() {
        if (!enabled() || !Files.isDirectory(backupDirectory())) return List.of();
        try (var files = Files.list(backupDirectory())) {
            return files.filter(Files::isRegularFile).filter(this::isBackupFile)
                    .sorted(Comparator.comparingLong(this::modifiedAt).reversed()).toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    ManifestRead readManifest() {
        if (!enabled() || !Files.isRegularFile(manifestFile())) return new ManifestRead(List.of(), true);
        try {
            List<MirrorBackupEntry> entries = GSON.fromJson(Files.readString(manifestFile(), StandardCharsets.UTF_8), MANIFEST_TYPE);
            if (entries == null) return new ManifestRead(List.of(), false);
            List<MirrorBackupEntry> safe = entries.stream().filter(this::validEntry)
                    .filter(entry -> safeBackupPath(entry.filename()) != null).toList();
            return new ManifestRead(List.copyOf(safe), safe.size() == entries.size());
        } catch (Exception exception) {
            return new ManifestRead(List.of(), false);
        }
    }

    void rebuildManifest(List<MirrorBackupEntry> entries) throws IOException {
        if (!enabled()) return;
        List<MirrorBackupEntry> safe = entries == null ? List.of() : entries.stream().filter(this::validEntry)
                .sorted(Comparator.comparingLong(MirrorBackupEntry::createdAt).reversed()).toList();
        writeManifest(safe);
        enforceRetention(safe);
    }

    Path safeBackupPath(String filename) {
        if (!enabled() || filename == null || filename.isBlank()) return null;
        Path relative;
        try {
            relative = Path.of(filename);
        } catch (RuntimeException exception) {
            return null;
        }
        if (relative.isAbsolute() || relative.getNameCount() != 1 || !relative.getFileName().toString().equals(filename)) return null;
        Path directory = backupDirectory();
        Path resolved = directory.resolve(relative).normalize();
        return resolved.getParent() != null && resolved.getParent().equals(directory) ? resolved : null;
    }

    private void enforceRetention(List<MirrorBackupEntry> entries) throws IOException {
        if (entries.size() <= MAX_RETENTION) return;
        Set<String> retained = new LinkedHashSet<>();
        List<MirrorBackupEntry> ordered = entries.stream()
                .sorted(Comparator.comparingLong(MirrorBackupEntry::createdAt).reversed()).toList();
        for (int index = 0; index < Math.min(RECENT_RETENTION, ordered.size()); index++) {
            retained.add(ordered.get(index).filename());
        }
        Map<String, String> oldestGeneration = new LinkedHashMap<>();
        for (int index = ordered.size() - 1; index >= 0; index--) {
            MirrorBackupEntry entry = ordered.get(index);
            oldestGeneration.putIfAbsent(entry.storageUuid() + ':' + entry.generation(), entry.filename());
        }
        for (String filename : oldestGeneration.values()) {
            if (retained.size() >= MAX_RETENTION) break;
            retained.add(filename);
        }
        Set<String> days = new LinkedHashSet<>();
        for (MirrorBackupEntry entry : ordered) {
            if (retained.size() >= MAX_RETENTION) break;
            String day = DAY.format(Instant.ofEpochMilli(entry.createdAt()));
            if (days.add(day)) retained.add(entry.filename());
        }
        for (MirrorBackupEntry entry : ordered) {
            if (retained.size() >= MAX_RETENTION) break;
            retained.add(entry.filename());
        }
        List<MirrorBackupEntry> kept = ordered.stream().filter(entry -> retained.contains(entry.filename())).toList();
        writeManifest(kept);
        for (MirrorBackupEntry entry : entries) {
            if (retained.contains(entry.filename())) continue;
            Path path = safeBackupPath(entry.filename());
            if (path != null && Files.isRegularFile(path)) fileOps.deleteIfExists(path);
        }
    }

    private void writeManifest(List<MirrorBackupEntry> entries) throws IOException {
        fileOps.createDirectories(root);
        Path temporary = manifestFile().resolveSibling("manifest.json." + UUID.randomUUID() + ".tmp");
        byte[] value = GSON.toJson(entries).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(value));
            channel.force(true);
        }
        fileOps.moveReplacing(temporary, manifestFile());
    }

    private void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private boolean validEntry(MirrorBackupEntry entry) {
        return entry != null && entry.filename() != null && !entry.filename().isBlank()
                && entry.createdAt() > 0L && entry.revision() >= 0L && entry.schemaVersion() > 0
                && entry.storageUuid() != null && !entry.storageUuid().isBlank()
                && entry.generation() > 0L && entry.sha256() != null && entry.sha256().matches("[0-9a-f]{64}")
                && entry.size() > 0L && entry.creditCount() >= 0 && entry.paymentCount() >= 0
                && entry.paylogCount() >= 0 && entry.eventCount() >= 0
                && entry.artifactType() == BackupArtifactType.RESTORABLE_HEALTHY && entry.healthy();
    }

    private boolean isBackupFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".zip") && !name.endsWith(".tmp");
    }

    private Path backupDirectory() {
        return root.resolve("backups");
    }

    private Path manifestFile() {
        return root.resolve("manifest.json");
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    record MirrorBackupEntry(String filename, long createdAt, long revision, int schemaVersion,
                             String storageUuid, long generation, String sha256, long size,
                             int creditCount, int paymentCount, int paylogCount, int eventCount,
                             BackupArtifactType artifactType, boolean healthy) { }
    record MirrorWriteResult(boolean success, Path artifact, MirrorBackupEntry entry, String message) {
        private static MirrorWriteResult failed(String message) {
            return new MirrorWriteResult(false, null, null, message == null ? "mirror backup failed" : message);
        }

        private static MirrorWriteResult disabled() {
            return new MirrorWriteResult(false, null, null, "mirror backup is disabled");
        }
    }
    record ManifestRead(List<MirrorBackupEntry> entries, boolean valid) { }
}
