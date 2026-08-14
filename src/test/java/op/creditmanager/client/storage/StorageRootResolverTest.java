package op.creditmanager.client.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageRootResolverTest {
    @TempDir Path temporary;
    private final StorageRootResolver resolver = new StorageRootResolver();

    @Test
    void standardFabricKeepsPersistentGameDirectory() {
        Path gameDir = temporary.resolve("minecraft");

        StorageRootResolver.StorageLocation location = resolver.resolve(gameDir, Map.of());

        assertTrue(location.resolved());
        assertEquals(StorageRootResolver.StorageEnvironment.STANDARD, location.environment());
        assertEquals(gameDir.toAbsolutePath().resolve("CreditManagerLogs"), location.canonicalRoot());
        assertNull(location.legacyRoot());
    }

    @Test
    void labymodSignalsResolveOutsideOverlay() {
        Path instance = temporary.resolve("LabyMod").resolve("instances").resolve("opsucht-instance");
        Path overlay = instance.resolve("overlay");
        Map<String, String> properties = Map.of(
                "net.labymod.launcher", "true",
                "net.labymod.mod-pack-id", "opsucht",
                "net.labymod.addons-dir", instance.resolve("addons").toString(),
                "net.labymod.fabric-dir", instance.resolve("loader").resolve("fabric").toString()
        );

        StorageRootResolver.StorageLocation location = resolver.resolve(overlay, properties);

        assertTrue(location.resolved());
        assertEquals(StorageRootResolver.StorageEnvironment.LABYMOD, location.environment());
        assertEquals(instance.resolve("CreditManagerLogs").toAbsolutePath(), location.canonicalRoot());
        assertEquals(overlay.resolve("CreditManagerLogs").toAbsolutePath(), location.legacyRoot());
        assertFalse(location.canonicalRoot().startsWith(overlay));
        assertEquals(StorageRootResolver.ResolutionSource.CROSS_VALIDATED, location.source());
    }

    @Test
    void conflictingLabymodSignalsFailClosed() {
        Path first = temporary.resolve("LabyMod").resolve("instances").resolve("first");
        Path second = temporary.resolve("LabyMod").resolve("instances").resolve("second");
        Map<String, String> properties = Map.of(
                "net.labymod.launcher", "true",
                "net.labymod.addons-dir", first.resolve("addons").toString(),
                "net.labymod.fabric-dir", second.resolve("loader").resolve("fabric").toString()
        );

        StorageRootResolver.StorageLocation location = resolver.resolve(first.resolve("overlay"), properties);

        assertFalse(location.resolved());
        assertEquals(StorageRootResolver.ResolutionSource.AMBIGUOUS, location.source());
        assertEquals(StorageRootResolver.StorageEnvironment.LABYMOD, location.environment());
    }

    @Test
    void launcherSignalWithoutValidatedInstanceRootFailsClosed() {
        StorageRootResolver.StorageLocation location = resolver.resolve(temporary.resolve("unknown-game"),
                Map.of("net.labymod.launcher", "true"));

        assertFalse(location.resolved());
        assertEquals(StorageRootResolver.ResolutionSource.AMBIGUOUS, location.source());
    }
}
