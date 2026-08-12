package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.schema.DatabaseSchemaManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DatabaseMigrationService {
    private static final Gson GSON = new Gson();
    private static final long MAX_ARCHIVE_ENTRY = 1_073_741_824L;
    private static final long MAX_ARCHIVE_TOTAL = 2_147_483_648L;
    private final DatabaseConnectionFactory connections;
    private final DatabaseSchemaManager schema;
    private final RecoveryFileOps fileOps;

    DatabaseMigrationService(DatabaseConnectionFactory connections, DatabaseSchemaManager schema, RecoveryFileOps fileOps) {
        this.connections = connections;
        this.schema = schema;
        this.fileOps = fileOps;
    }

    void recoverInterruptedMigration() throws IOException {
        Path journal = journalPath();
        if (!Files.isRegularFile(journal)) return;
        @SuppressWarnings("unchecked")
        Map<String, String> state = GSON.fromJson(Files.readString(journal, StandardCharsets.UTF_8), Map.class);
        if (state == null || "COMPLETED".equals(state.get("phase"))) return;
        Path active = FileManager.getDatabaseStorageFile().toAbsolutePath().normalize();
        Path original = safeRecordedPath(state.get("original"));
        if (!Files.exists(active) && original != null && Files.isRegularFile(original)) {
            moveWithoutReplacing(original, active);
            state.put("phase", "ROLLED_BACK_AFTER_INTERRUPTION");
            writeJournal(state);
        }
    }

    void migrateV7ToV8(Path activeBase, int installedVersion) throws Exception {
        if (installedVersion >= DatabaseManager.SCHEMA_VERSION) return;
        if (installedVersion < 1 || installedVersion > 7) {
            throw new DatabaseSchemaManager.SchemaValidationException("Unsupported schema version for staged migration: " + installedVersion);
        }
        Path activeStorage = storagePath(activeBase);
        if (!Files.isRegularFile(activeStorage)) throw new IOException("Active database file is missing");
        long[] sourceCounts;
        boolean hasData;
        try (Connection active = connections.open(activeBase)) {
            sourceCounts = domainCounts(active);
            hasData = sum(sourceCounts) > 0L;
            if (hasData) createAndValidateSafetyBackup(active, sourceCounts);
        }

        Path root = FileManager.getRecoveryDirectory().resolve("migration-v8-" + System.currentTimeMillis() + '-' + UUID.randomUUID());
        fileOps.createDirectories(root);
        Path stageStorage = root.resolve("creditmanager-stage.mv.db");
        Path stageBase = root.resolve("creditmanager-stage");
        Path original = FileManager.getQuarantineDirectory().resolve("creditmanager-v" + installedVersion + "-original-"
                + System.currentTimeMillis() + '-' + UUID.randomUUID() + ".mv.db");
        Map<String, String> journal = new LinkedHashMap<>();
        journal.put("phase", "DISCOVERED");
        journal.put("active", activeStorage.toString());
        journal.put("staging", stageStorage.toString());
        journal.put("original", original.toString());
        journal.put("source_sha256", sha256(activeStorage));
        writeJournal(journal);

        fileOps.copy(activeStorage, stageStorage, StandardCopyOption.COPY_ATTRIBUTES);
        journal.put("phase", hasData ? "SAFETY_BACKUP_CONFIRMED" : "STAGING_COPIED");
        writeJournal(journal);
        try (Connection staging = connections.open(stageBase)) {
            staging.setAutoCommit(false);
            try {
                schema.migrateV7ToV8(staging);
                schema.validateRequiredSchema(staging);
                long[] migratedCounts = domainCounts(staging);
                if (!java.util.Arrays.equals(sourceCounts, migratedCounts)) throw new IOException("Schema migration row counts changed");
                staging.commit();
            } catch (Exception exception) {
                staging.rollback();
                throw exception;
            }
        }
        journal.put("phase", "STAGING_VALIDATED");
        writeJournal(journal);

        fileOps.createDirectories(original.getParent());
        moveWithoutReplacing(activeStorage, original);
        journal.put("phase", "ORIGINAL_QUARANTINED");
        writeJournal(journal);
        try {
            moveWithoutReplacing(stageStorage, activeStorage);
            try (Connection installed = connections.open(activeBase)) {
                schema.validateRequiredSchema(installed);
                if (!java.util.Arrays.equals(sourceCounts, domainCounts(installed))) throw new IOException("Post-install row counts changed");
            }
            journal.put("phase", "COMPLETED");
            journal.put("installed_sha256", sha256(activeStorage));
            writeJournal(journal);
        } catch (Exception installFailure) {
            Path failed = root.resolve("failed-install.mv.db");
            if (Files.exists(activeStorage)) moveWithoutReplacing(activeStorage, failed);
            moveWithoutReplacing(original, activeStorage);
            if (!journal.get("source_sha256").equals(sha256(activeStorage))) {
                throw new IOException("Migration rollback checksum mismatch", installFailure);
            }
            journal.put("phase", "ROLLED_BACK");
            journal.put("failure", installFailure.toString());
            writeJournal(journal);
            throw installFailure;
        }
    }

    private void createAndValidateSafetyBackup(Connection active, long[] sourceCounts) throws Exception {
        Path backup = FileManager.getBackupDirectory().resolve("creditmanager_pre_v8_" + System.currentTimeMillis() + ".zip");
        fileOps.createDirectories(backup.getParent());
        try (Statement statement = active.createStatement()) {
            statement.execute("BACKUP TO '" + backup.toAbsolutePath().normalize().toString().replace("'", "''") + "'");
        }
        Path validation = FileManager.getRecoveryValidationDirectory().resolve("migration-backup-" + UUID.randomUUID());
        try {
            Path database = extractSingleDatabase(backup, validation);
            String name = database.getFileName().toString();
            Path base = database.resolveSibling(name.substring(0, name.length() - ".mv.db".length()));
            try (Connection restored = connections.open(base)) {
                if (!java.util.Arrays.equals(sourceCounts, domainCounts(restored))) {
                    throw new IOException("Pre-migration backup row counts differ");
                }
            }
        } catch (Exception exception) {
            throw new IOException("Required pre-migration backup validation failed", exception);
        } finally {
            deleteTree(validation);
        }
    }

    private Path extractSingleDatabase(Path archive, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);
        List<Path> databases = new ArrayList<>();
        long total = 0L;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > 256) throw new IOException("Backup contains too many entries");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getSize() > MAX_ARCHIVE_ENTRY) throw new IOException("Backup entry is too large");
                if (entry.getSize() > 16_777_216L && entry.getCompressedSize() > 0L
                        && entry.getSize() / entry.getCompressedSize() > 200L) throw new IOException("Backup entry compression ratio is unsafe");
                Path target = targetRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetRoot)) throw new IOException("Unsafe backup entry");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                long written = 0L;
                try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[16_384];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        written += read;
                        total += read;
                        if (written > MAX_ARCHIVE_ENTRY || total > MAX_ARCHIVE_TOTAL) throw new IOException("Backup exceeds extraction limit");
                        output.write(buffer, 0, read);
                    }
                }
                if (target.getFileName().toString().endsWith(".mv.db")) databases.add(target);
            }
        }
        if (databases.size() != 1) throw new IOException("Backup must contain exactly one H2 database");
        return databases.getFirst();
    }

    private long[] domainCounts(Connection connection) throws Exception {
        long[] counts = new long[4];
        try (Statement statement = connection.createStatement()) {
            String[] tables = {"credits", "payments", "credit_events", "paylogs"};
            for (int index = 0; index < tables.length; index++) {
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tables[index])) {
                    result.next();
                    counts[index] = result.getLong(1);
                }
            }
        }
        return counts;
    }

    private long sum(long[] values) {
        long total = 0L;
        for (long value : values) total = Math.addExact(total, value);
        return total;
    }

    private Path safeRecordedPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Path path = Path.of(raw).toAbsolutePath().normalize();
        Path recovery = FileManager.getRecoveryDirectory().toAbsolutePath().normalize();
        return path.startsWith(recovery) ? path : null;
    }

    private Path journalPath() {
        return FileManager.getRecoveryDirectory().resolve("migration-v8-journal.json");
    }

    private void writeJournal(Map<String, String> values) throws IOException {
        Path journal = journalPath();
        fileOps.createDirectories(journal.getParent());
        Path temporary = journal.resolveSibling(journal.getFileName() + ".tmp");
        fileOps.writeString(temporary, GSON.toJson(values), StandardCharsets.UTF_8);
        fileOps.moveReplacing(temporary, journal);
    }

    private Path storagePath(Path base) {
        return base.resolveSibling(base.getFileName() + ".mv.db");
    }

    private void moveWithoutReplacing(Path source, Path target) throws IOException {
        fileOps.moveWithoutReplacing(source, target);
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

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
