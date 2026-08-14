package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupCatalogTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;
    private DatabaseCoordinator database;

    @BeforeEach
    void initializeDatabase() throws Exception {
        Field field = dataDirectoryField();
        previousDirectory = (Path) field.get(null);
        field.set(null, dataDirectory);
        FileManager.initialize();
        database = new DatabaseCoordinator();
        database.initialize();
        addCredit(new UUID(20L, 1L), "first");
    }

    @AfterEach
    void restoreDataDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        DataHealth.clearReasons();
    }

    @Test
    void missingAndCorruptManifestAreRebuiltFromThePhysicalZip() throws Exception {
        assertTrue(database.createBackup());
        Path manifest = FileManager.getBackupManifestFile();
        Files.delete(manifest);

        List<DatabaseManager.BackupManifestEntry> missingManifest = database.listBackups();

        assertEquals(1, missingManifest.size());
        assertTrue(Files.isRegularFile(manifest));
        Files.writeString(manifest, "{broken");

        List<DatabaseManager.BackupManifestEntry> corruptManifest = database.listBackups();

        assertEquals(1, corruptManifest.size());
        assertEquals(missingManifest.getFirst().fileName(), corruptManifest.getFirst().fileName());
        assertTrue(Files.readString(manifest).startsWith("["));
    }

    @Test
    void logicalHealthErrorAllowsForensicSnapshotButNeverAutomaticRestore() throws Exception {
        try (Connection connection = database.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO data_health_records (id,record_type,severity,title,message,status,created_at) VALUES ('"
                    + UUID.randomUUID() + "','TEST_SNAPSHOT','ERROR','test','test','OPEN',1)");
        }

        assertFalse(database.createBackup());
        assertTrue(database.createRecoverySnapshot());
        List<DatabaseManager.BackupManifestEntry> backups = database.listBackups();

        assertEquals(1, backups.size());
        assertEquals(DatabaseManager.BackupArtifactType.RECOVERY_SNAPSHOT, backups.getFirst().artifactType());
        assertFalse(backups.getFirst().automaticRestoreEligible());
        assertFalse(database.restoreLatestValidBackup());
        assertEquals(1, database.loadCreditState().credits().size());
    }

    @Test
    void orphanZipAfterManifestFailureIsDiscoveredOnTheNextCatalogScan() throws Exception {
        RecoveryFileOps failingManifest = new RecoveryFileOps() {
            @Override
            void moveReplacing(Path source, Path target) throws IOException {
                if (target.toAbsolutePath().normalize().equals(FileManager.getBackupManifestFile().toAbsolutePath().normalize())) {
                    throw new IOException("injected manifest move failure");
                }
                super.moveReplacing(source, target);
            }
        };
        DatabaseCoordinator failing = new DatabaseCoordinator(DatabaseFaultInjector.NONE, failingManifest);

        assertFalse(failing.createBackup());
        try (var files = Files.list(FileManager.getBackupDirectory())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".zip")));
        }

        assertEquals(1, new DatabaseCoordinator().listBackups().size());
        assertTrue(Files.isRegularFile(FileManager.getBackupManifestFile()));
    }

    @Test
    void trustworthyUnhealthyMetadataExcludesTheNewerAutomaticRestoreCandidate() throws Exception {
        assertTrue(database.createBackup());
        Thread.sleep(5L);
        addCredit(new UUID(20L, 2L), "second");
        assertTrue(database.createBackup());
        List<DatabaseManager.BackupManifestEntry> entries = database.listBackups().stream()
                .sorted(Comparator.comparingLong(DatabaseManager.BackupManifestEntry::createdAt).reversed()).toList();
        DatabaseManager.BackupManifestEntry newest = entries.getFirst();
        List<DatabaseManager.BackupManifestEntry> changed = new ArrayList<>();
        for (DatabaseManager.BackupManifestEntry entry : entries) {
            changed.add(entry.fileName().equals(newest.fileName())
                    ? new DatabaseManager.BackupManifestEntry(entry.fileName(), entry.createdAt(), entry.schemaVersion(), entry.revision(), entry.creditCount(), entry.paymentCount(), entry.paylogCount(), entry.eventCount(), false, entry.format(), entry.sha256(), entry.size(), entry.archiveVersion())
                    : entry);
        }
        Files.writeString(FileManager.getBackupManifestFile(), new Gson().toJson(changed));

        assertTrue(database.restoreLatestValidBackup());

        DatabaseManager.DatabaseState restored = database.loadCreditState();
        assertEquals(1, restored.credits().size());
        assertEquals("first", restored.credits().getFirst().getNote());
    }

    @Test
    void archiveWithTwoDatabaseMembersIsRejected() throws Exception {
        byte[] databaseBytes = Files.readAllBytes(FileManager.getDatabaseStorageFile());
        Path archive = FileManager.getBackupDirectory().resolve("ambiguous.zip");
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("first.mv.db"));
            output.write(databaseBytes);
            output.closeEntry();
            output.putNextEntry(new ZipEntry("second.mv.db"));
            output.write(databaseBytes);
            output.closeEntry();
        }

        assertTrue(database.listBackups().stream().noneMatch(entry -> "ambiguous.zip".equals(entry.fileName())));
        assertTrue(Files.isRegularFile(archive));
    }

    @Test
    void manifestPathTraversalIsIgnoredWithoutReadingOutsideTheBackupDirectory() throws Exception {
        Path outside = dataDirectory.resolve("outside.mv.db");
        Files.copy(FileManager.getDatabaseStorageFile(), outside);
        byte[] original = Files.readAllBytes(outside);
        Files.createDirectories(FileManager.getBackupDirectory());
        String malicious = "[{\"fileName\":\"../outside.mv.db\",\"createdAt\":1,\"healthy\":true}]";
        Files.writeString(FileManager.getBackupManifestFile(), malicious);

        assertTrue(database.listBackups().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(outside));
    }

    @Test
    void activeManifestAndPhysicalRetentionStayAtThirtyTwoWithoutDeletingTheLastHealthyBackup() throws Exception {
        for (int index = 0; index < 33; index++) {
            assertTrue(database.createBackup());
            Thread.sleep(2L);
        }

        assertEquals(32, database.listBackups().size());
        try (var files = Files.list(FileManager.getBackupDirectory())) {
            assertEquals(32L, files.filter(path -> path.getFileName().toString().endsWith(".zip")).count());
        }
        Path retired = FileManager.getRecoveryDirectory().resolve("retired-backups");
        try (var files = Files.list(retired)) {
            assertEquals(1L, files.filter(path -> path.getFileName().toString().endsWith(".zip")).count());
        }
        assertTrue(database.listBackups().stream().anyMatch(DatabaseManager.BackupManifestEntry::healthy));
    }

    @Test
    void retiredBackupRetentionStaysBoundedAcrossOneThousandArtifactsAndPreservesBothTypes() throws Exception {
        assertTrue(database.createBackup());
        Path activeBackup;
        try (var files = Files.list(FileManager.getBackupDirectory())) {
            activeBackup = files.filter(path -> path.getFileName().toString().endsWith(".zip")).findFirst().orElseThrow();
        }
        Path retired = FileManager.getRecoveryDirectory().resolve("retired-backups");
        Files.createDirectories(retired);
        Path healthy = retired.resolve("healthy.zip");
        Files.copy(activeBackup, healthy);
        Path snapshot = retired.resolve("creditmanager_recovery_snapshot_latest.zip");
        Files.copy(activeBackup, snapshot);
        for (int index = 0; index < 1_000; index++) {
            Files.writeString(retired.resolve(String.format("invalid-%03d.zip", index)), "not a backup " + index);
        }

        Method prune = DatabaseCoordinator.class.getDeclaredMethod("pruneRetiredBackups", Path.class);
        prune.setAccessible(true);
        prune.invoke(database, retired);

        try (var files = Files.list(retired)) {
            assertEquals(64L, files.filter(Files::isRegularFile).count());
        }
        assertTrue(Files.isRegularFile(healthy));
        assertTrue(Files.isRegularFile(snapshot));
    }

    @Test
    void backupDirectoryWriteFailureLeavesDatabaseAndCatalogIntactAndCanBeRetried() throws Exception {
        Path backupDirectory = FileManager.getBackupDirectory().toAbsolutePath().normalize();
        RecoveryFileOps diskFull = new RecoveryFileOps() {
            @Override
            void createDirectories(Path directory) throws IOException {
                if (directory.toAbsolutePath().normalize().equals(backupDirectory)) {
                    throw new IOException("injected disk full");
                }
                super.createDirectories(directory);
            }
        };
        DatabaseCoordinator failing = new DatabaseCoordinator(DatabaseFaultInjector.NONE, diskFull);
        failing.initialize();
        long revision = failing.revision();

        assertFalse(failing.createBackup());

        assertEquals(revision, failing.revision());
        DatabaseManager.DatabaseState state = failing.loadCreditState();
        assertEquals(List.of(new UUID(20L, 1L)), state.credits().stream().map(CreditEntry::getId).toList());
        assertEquals("first", state.credits().getFirst().getNote());
        assertTrue(state.payments().isEmpty());
        assertTrue(state.events().isEmpty());
        assertTrue(failing.listBackups().isEmpty());
        assertTrue(database.createBackup());
        assertEquals(1, database.listBackups().size());
    }

    @Test
    void retiredBackupDeleteFailurePreservesProtectedArtifactsAndRetryConverges() throws Exception {
        assertTrue(database.createBackup());
        Path activeBackup;
        try (var files = Files.list(FileManager.getBackupDirectory())) {
            activeBackup = files.filter(path -> path.getFileName().toString().endsWith(".zip")).findFirst().orElseThrow();
        }
        Path retired = FileManager.getRecoveryDirectory().resolve("retired-backups");
        Files.createDirectories(retired);
        Path healthy = retired.resolve("healthy.zip");
        Path snapshot = retired.resolve("creditmanager_recovery_snapshot_latest.zip");
        Files.copy(activeBackup, healthy);
        Files.copy(activeBackup, snapshot);
        for (int index = 0; index < 65; index++) {
            Files.writeString(retired.resolve(String.format("invalid-%03d.zip", index)), "not a backup " + index);
        }
        RecoveryFileOps failingDelete = new RecoveryFileOps() {
            private boolean failed;

            @Override
            void deleteIfExists(Path target) throws IOException {
                if (!failed) {
                    failed = true;
                    throw new IOException("injected delete failure");
                }
                super.deleteIfExists(target);
            }
        };
        DatabaseCoordinator failing = new DatabaseCoordinator(DatabaseFaultInjector.NONE, failingDelete);

        invokeRetiredPrune(failing, retired);

        assertTrue(Files.isRegularFile(healthy));
        assertTrue(Files.isRegularFile(snapshot));
        invokeRetiredPrune(database, retired);
        try (var files = Files.list(retired)) {
            assertEquals(64L, files.filter(Files::isRegularFile).count());
        }
        assertTrue(Files.isRegularFile(healthy));
        assertTrue(Files.isRegularFile(snapshot));
    }

    private void invokeRetiredPrune(DatabaseCoordinator coordinator, Path retired) throws Exception {
        Method prune = DatabaseCoordinator.class.getDeclaredMethod("pruneRetiredBackups", Path.class);
        prune.setAccessible(true);
        prune.invoke(coordinator, retired);
    }

    private void addCredit(UUID id, String note) {
        CreditEntry credit = new CreditEntry(id, "backup-deal", "creditor", "debtor-" + id.getLeastSignificantBits(), 10_000L, null, note);
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of())));
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}
