package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.db.dao.DatabaseMetadataDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageIdentityGuardTest {
    @TempDir Path temporary;
    private final DatabaseConnectionFactory connections = new DatabaseConnectionFactory();
    private final DatabaseMetadataDao metadata = new DatabaseMetadataDao();
    private final RecoveryFileOps files = new RecoveryFileOps();
    private StorageIdentityGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        connections.loadDriver();
        guard = new StorageIdentityGuard(metadata, files);
    }

    @Test
    void databaseMetadataAndAtomicSidecarMustMatch() throws Exception {
        Path base = temporary.resolve("creditmanager");
        Path sidecar = temporary.resolve("storage_identity.json");
        StorageIdentityGuard.StorageIdentity identity = guard.newIdentity("instance-a", 1L);
        try (var connection = connections.createFresh(base); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata(meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            guard.writeDatabaseIdentity(connection, identity);
            guard.writeSidecar(sidecar, identity);

            StorageIdentityGuard.Inspection inspection = guard.inspect(connection, sidecar);

            assertEquals(StorageIdentityGuard.IdentityStatus.MATCH, inspection.status());
            assertEquals(identity.storageUuid(), inspection.databaseIdentity().storageUuid());
        }
        assertTrue(Files.isRegularFile(sidecar));
        try (var files = Files.list(temporary)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp.")));
        }
    }

    @Test
    void differentDatabaseIdentityIsRejected() throws Exception {
        Path base = temporary.resolve("creditmanager");
        Path sidecar = temporary.resolve("storage_identity.json");
        StorageIdentityGuard.StorageIdentity sidecarIdentity = guard.newIdentity("instance-a", 1L);
        StorageIdentityGuard.StorageIdentity databaseIdentity = guard.newIdentity("instance-a", 1L);
        guard.writeSidecar(sidecar, sidecarIdentity);
        try (var connection = connections.createFresh(base); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata(meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            guard.writeDatabaseIdentity(connection, databaseIdentity);

            assertEquals(StorageIdentityGuard.IdentityStatus.MISMATCH, guard.inspect(connection, sidecar).status());
        }
    }

    @Test
    void existingDatabaseCanReconstructOnlyAMissingSidecar() throws Exception {
        Path base = temporary.resolve("creditmanager");
        Path sidecar = temporary.resolve("storage_identity.json");
        StorageIdentityGuard.StorageIdentity identity = guard.newIdentity("instance-a", 3L);
        try (var connection = connections.createFresh(base); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata(meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            guard.writeDatabaseIdentity(connection, identity);
            assertEquals(StorageIdentityGuard.IdentityStatus.SIDECAR_MISSING, guard.inspect(connection, sidecar).status());
            guard.writeSidecar(sidecar, identity);
            assertEquals(StorageIdentityGuard.IdentityStatus.MATCH, guard.inspect(connection, sidecar).status());
        }
    }

    @Test
    void malformedSidecarFailsClosed() throws Exception {
        Path base = temporary.resolve("creditmanager");
        Path sidecar = temporary.resolve("storage_identity.json");
        Files.writeString(sidecar, "{invalid", StandardCharsets.UTF_8);
        try (var connection = connections.createFresh(base); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata(meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            guard.writeDatabaseIdentity(connection, guard.newIdentity("instance-a", 1L));

            assertThrows(StorageIdentityGuard.StorageIdentityException.class, () -> guard.inspect(connection, sidecar));
        }
    }
}
