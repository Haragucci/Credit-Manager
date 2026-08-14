package op.creditmanager.client.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupMirrorPathResolverTest {
    @TempDir Path temporary;

    @Test
    void stableMirrorIsOutsidePrimaryInstanceAndOverlayTrees() {
        Path primary = temporary.resolve("instance").resolve("creditmanager");
        Path legacy = temporary.resolve("overlay").resolve("creditmanager");
        StorageRootResolver.StorageLocation location = new StorageRootResolver.StorageLocation(primary, legacy,
                StorageRootResolver.StorageEnvironment.LABYMOD, "instance-key",
                StorageRootResolver.ResolutionSource.CROSS_VALIDATED, true, List.of());
        BackupMirrorPathResolver resolver = new BackupMirrorPathResolver();

        BackupMirrorPathResolver.Resolution allowed = resolver.resolve(primary, location, null, null,
                temporary.resolve("independent-mirror"));
        assertTrue(allowed.enabled());
        assertNotEquals(primary.toAbsolutePath().normalize(), allowed.root());
        assertFalse(allowed.root().startsWith(primary.getParent().toAbsolutePath().normalize()));
        assertFalse(allowed.root().startsWith(legacy.getParent().toAbsolutePath().normalize()));

        assertFalse(resolver.resolve(primary, location, null, null, primary.resolve("backups")).enabled());
        assertFalse(resolver.resolve(primary, location, null, null, legacy.getParent()).enabled());
    }
}
