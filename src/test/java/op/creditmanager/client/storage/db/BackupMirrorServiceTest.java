package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.db.DatabaseManager.BackupArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupMirrorServiceTest {
    @TempDir Path temporary;

    @Test
    void copyIsChecksumVerifiedAndManifestCanBeRebuiltWithoutTempArtifacts() throws Exception {
        Path source = temporary.resolve("source.zip");
        Files.writeString(source, "validated-backup");
        BackupMirrorService service = new BackupMirrorService(temporary.resolve("mirror"), new RecoveryFileOps());
        BackupMirrorService.MirrorBackupEntry entry = entry(source, "creditmanager_backup_1.zip", 1L);

        BackupMirrorService.MirrorWriteResult result = service.mirror(source, entry);
        assertTrue(result.success());
        assertEquals(entry.sha256(), sha256(result.artifact()));
        assertEquals(1, service.readManifest().entries().size());

        Files.writeString(service.root().resolve("backups").resolve("unfinished.zip.tmp"), "partial");
        Files.writeString(service.root().resolve("manifest.json"), "not-json");
        assertFalse(service.readManifest().valid());
        assertEquals(1, service.candidateFiles().size());
        service.rebuildManifest(java.util.List.of(entry));
        assertTrue(service.readManifest().valid());
        assertEquals(entry, service.readManifest().entries().getFirst());
    }

    @Test
    void divergentArtifactCannotReplacePublishedMirrorFilename() throws Exception {
        BackupMirrorService service = new BackupMirrorService(temporary.resolve("mirror"), new RecoveryFileOps());
        Path first = temporary.resolve("first.zip");
        Path second = temporary.resolve("second.zip");
        Files.writeString(first, "one");
        Files.writeString(second, "two");
        assertTrue(service.mirror(first, entry(first, "same.zip", 1L)).success());
        assertFalse(service.mirror(second, entry(second, "same.zip", 2L)).success());
        assertEquals("one", Files.readString(service.root().resolve("backups").resolve("same.zip")));
    }

    @Test
    void independentRetentionKeepsNewestAndAnOldGenerationAnchor() throws Exception {
        BackupMirrorService service = new BackupMirrorService(temporary.resolve("mirror"), new RecoveryFileOps());
        Path source = temporary.resolve("source.zip");
        Files.writeString(source, "backup");
        long base = 1_700_000_000_000L;
        for (int index = 0; index < 70; index++) {
            BackupMirrorService.MirrorBackupEntry value = new BackupMirrorService.MirrorBackupEntry(
                    "backup-" + index + ".zip", base + index * 86_400_000L, index,
                    DatabaseManager.SCHEMA_VERSION, "2aa26ba6-5831-4a77-a785-8bf907847878",
                    index < 5 ? 1L : 2L, sha256(source), Files.size(source), 1, 0, 0, 0,
                    BackupArtifactType.RESTORABLE_HEALTHY, true);
            assertTrue(service.mirror(source, value).success());
        }
        java.util.List<BackupMirrorService.MirrorBackupEntry> retained = service.readManifest().entries();
        assertTrue(retained.size() <= 64);
        assertTrue(retained.stream().anyMatch(value -> value.filename().equals("backup-69.zip")));
        assertTrue(retained.stream().anyMatch(value -> value.filename().equals("backup-0.zip")));
        assertTrue(retained.stream().anyMatch(value -> value.filename().equals("backup-5.zip")));
    }

    private BackupMirrorService.MirrorBackupEntry entry(Path source, String name, long revision) throws Exception {
        return new BackupMirrorService.MirrorBackupEntry(name, System.currentTimeMillis(), revision,
                DatabaseManager.SCHEMA_VERSION, "2aa26ba6-5831-4a77-a785-8bf907847878", 1L,
                sha256(source), Files.size(source), 1, 0, 0, 0,
                BackupArtifactType.RESTORABLE_HEALTHY, true);
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
