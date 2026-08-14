package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.StorageRootResolver;
import op.creditmanager.client.storage.ProcessStorageLease;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StorageTestScope implements AutoCloseable {
    private static final List<String> FIELDS = List.of("dataDirectory", "initializedDirectory", "storageLocation",
            "backupMirrorDirectory", "storageLease", "storageAccessState", "dataDirectoryCreated");
    private final Map<Field, Object> previous = new LinkedHashMap<>();

    StorageTestScope() throws Exception {
        for (String name : FIELDS) {
            Field field = FileManager.class.getDeclaredField(name);
            field.setAccessible(true);
            previous.put(field, field.get(null));
        }
    }

    void configureExternal(Path root) throws Exception {
        configure(root, null, new StorageRootResolver.StorageLocation(root, null,
                StorageRootResolver.StorageEnvironment.STANDARD, "test-instance",
                StorageRootResolver.ResolutionSource.STANDARD_GAME_DIR, true, List.of("test")),
                FileManager.StorageAccessState.EXTERNALLY_MANAGED);
    }

    void configureExternal(Path root, Path mirror) throws Exception {
        configure(root, mirror, new StorageRootResolver.StorageLocation(root, null,
                        StorageRootResolver.StorageEnvironment.STANDARD, "test-instance",
                        StorageRootResolver.ResolutionSource.STANDARD_GAME_DIR, true, List.of("test")),
                FileManager.StorageAccessState.EXTERNALLY_MANAGED);
    }

    void configurePrimary(Path root, Path mirror) throws Exception {
        ProcessStorageLease lease = null;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (lease == null && System.nanoTime() < deadline) {
            lease = ProcessStorageLease.tryAcquire(root).orElse(null);
            if (lease == null) Thread.sleep(25L);
        }
        if (lease == null) throw new IllegalStateException("primary storage lease was not released in time");
        configure(root, mirror, new StorageRootResolver.StorageLocation(root, null,
                        StorageRootResolver.StorageEnvironment.STANDARD, "test-primary",
                        StorageRootResolver.ResolutionSource.STANDARD_GAME_DIR, true, List.of("test-primary")),
                FileManager.StorageAccessState.PRIMARY);
        set("storageLease", lease);
    }

    void configure(Path root, StorageRootResolver.StorageLocation location, FileManager.StorageAccessState state) throws Exception {
        configure(root, null, location, state);
    }

    void configure(Path root, Path mirror, StorageRootResolver.StorageLocation location, FileManager.StorageAccessState state) throws Exception {
        Files.createDirectories(root);
        set("dataDirectory", root.toAbsolutePath().normalize());
        set("initializedDirectory", root.toAbsolutePath().normalize());
        set("storageLocation", location);
        set("backupMirrorDirectory", mirror == null ? null : mirror.toAbsolutePath().normalize());
        set("storageLease", null);
        set("storageAccessState", state);
        set("dataDirectoryCreated", false);
        DataHealth.clearReasons();
    }

    void setAccessState(FileManager.StorageAccessState state) throws Exception {
        set("storageAccessState", state);
    }

    private void set(String name, Object value) throws Exception {
        Field field = FileManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Override
    public void close() throws Exception {
        FileManager.shutdown();
        for (Map.Entry<Field, Object> entry : previous.entrySet()) entry.getKey().set(null, entry.getValue());
    }
}
