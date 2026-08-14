package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import op.creditmanager.client.storage.db.dao.DatabaseMetadataDao;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class StorageIdentityGuard {
    private static final Gson GSON = new Gson();
    private final DatabaseMetadataDao metadata;
    private final RecoveryFileOps fileOps;

    StorageIdentityGuard(DatabaseMetadataDao metadata, RecoveryFileOps fileOps) {
        this.metadata = metadata;
        this.fileOps = fileOps;
    }

    StorageIdentity newIdentity(String instanceKey, long generation) {
        return new StorageIdentity(UUID.randomUUID().toString(), instanceKey == null ? "unknown" : instanceKey,
                System.currentTimeMillis(), Math.max(1L, generation));
    }

    Inspection inspect(Connection connection, Path sidecar) throws SQLException, IOException {
        Optional<StorageIdentity> database = readDatabaseIdentity(connection);
        Optional<StorageIdentity> file = readSidecar(sidecar);
        if (database.isEmpty() && file.isEmpty()) return new Inspection(IdentityStatus.MISSING_BOTH, null, null);
        if (database.isEmpty()) return new Inspection(IdentityStatus.DATABASE_IDENTITY_MISSING, null, file.get());
        if (file.isEmpty()) return new Inspection(IdentityStatus.SIDECAR_MISSING, database.get(), null);
        StorageIdentity db = database.get();
        StorageIdentity stored = file.get();
        IdentityStatus status = db.storageUuid().equals(stored.storageUuid()) && db.generation() == stored.generation()
                ? IdentityStatus.MATCH : IdentityStatus.MISMATCH;
        return new Inspection(status, db, stored);
    }

    Optional<StorageIdentity> readDatabaseIdentity(Connection connection) throws SQLException {
        String uuid = metadata.read(connection, "storage_uuid");
        String createdAt = metadata.read(connection, "storage_created_at");
        String generation = metadata.read(connection, "storage_generation");
        if (uuid == null && createdAt == null && generation == null) return Optional.empty();
        if (uuid == null || createdAt == null || generation == null) throw new StorageIdentityException("Database storage identity is incomplete");
        return Optional.of(validated(uuid, metadata.read(connection, "storage_instance_key"), createdAt, generation));
    }

    Optional<StorageIdentity> readSidecar(Path sidecar) throws IOException {
        if (!Files.isRegularFile(sidecar)) return Optional.empty();
        try {
            StorageIdentity identity = GSON.fromJson(Files.readString(sidecar, StandardCharsets.UTF_8), StorageIdentity.class);
            if (identity == null) throw new StorageIdentityException("Storage identity sidecar is empty");
            return Optional.of(validated(identity.storageUuid(), identity.instanceKey(), String.valueOf(identity.createdAt()),
                    String.valueOf(identity.generation())));
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new StorageIdentityException("Storage identity sidecar is invalid", exception);
        }
    }

    void writeDatabaseIdentity(Connection connection, StorageIdentity identity) throws SQLException {
        metadata.write(connection, "storage_uuid", identity.storageUuid());
        metadata.write(connection, "storage_created_at", String.valueOf(identity.createdAt()));
        metadata.write(connection, "storage_generation", String.valueOf(identity.generation()));
        metadata.write(connection, "storage_instance_key", identity.instanceKey());
    }

    void writeSidecar(Path sidecar, StorageIdentity identity) throws IOException {
        Optional<StorageIdentity> existing = readSidecar(sidecar);
        if (existing.isPresent()) {
            if (sameIdentity(existing.get(), identity)) return;
            throw new StorageIdentityException("Refusing to overwrite a different storage identity sidecar");
        }
        fileOps.createDirectories(sidecar.getParent());
        Path temporary = sidecar.resolveSibling(sidecar.getFileName() + ".tmp." + UUID.randomUUID());
        byte[] value = GSON.toJson(identity).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(value));
            channel.force(true);
        }
        try {
            fileOps.moveWithoutReplacing(temporary, sidecar);
        } catch (IOException exception) {
            fileOps.deleteIfExists(temporary);
            throw exception;
        }
    }

    private StorageIdentity validated(String uuid, String instanceKey, String createdAtValue, String generationValue) {
        UUID parsed = UUID.fromString(uuid);
        long createdAt = Long.parseLong(createdAtValue);
        long generation = Long.parseLong(generationValue);
        if (createdAt <= 0L || generation <= 0L) throw new StorageIdentityException("Storage identity values are invalid");
        String key = instanceKey == null || instanceKey.isBlank() ? "unknown" : instanceKey;
        return new StorageIdentity(parsed.toString(), key, createdAt, generation);
    }

    private boolean sameIdentity(StorageIdentity left, StorageIdentity right) {
        return Objects.equals(left.storageUuid(), right.storageUuid()) && left.generation() == right.generation()
                && left.createdAt() == right.createdAt();
    }

    enum IdentityStatus { MATCH, MISSING_BOTH, DATABASE_IDENTITY_MISSING, SIDECAR_MISSING, MISMATCH }
    record Inspection(IdentityStatus status, StorageIdentity databaseIdentity, StorageIdentity sidecarIdentity) { }
    record StorageIdentity(String storageUuid, String instanceKey, long createdAt, long generation) { }

    static final class StorageIdentityException extends IllegalStateException {
        StorageIdentityException(String message) { super(message); }
        StorageIdentityException(String message, Throwable cause) { super(message, cause); }
    }
}
