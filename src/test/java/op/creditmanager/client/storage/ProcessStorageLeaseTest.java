package op.creditmanager.client.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProcessStorageLeaseTest {
    @TempDir Path temporary;

    @Test
    void onlyOneLeaseCanBeHeldForTheSameStorageRoot() throws Exception {
        ProcessStorageLease first = ProcessStorageLease.tryAcquire(temporary).orElseThrow();
        try {
            assertTrue(first.isHeld());
            assertTrue(Files.isRegularFile(temporary.resolve(".creditmanager.lock")));
            assertTrue(ProcessStorageLease.tryAcquire(temporary).isEmpty());
        } finally {
            first.close();
        }
    }

    @Test
    void staleLockFileDoesNotPreventSafeHandover() throws Exception {
        try (ProcessStorageLease first = ProcessStorageLease.tryAcquire(temporary).orElseThrow()) {
            assertTrue(first.isHeld());
        }

        assertTrue(Files.exists(temporary.resolve(".creditmanager.lock")));
        try (ProcessStorageLease second = ProcessStorageLease.tryAcquire(temporary).orElseThrow()) {
            assertTrue(second.isHeld());
        }
    }

    @Test
    void differentStorageRootsAreIsolated() throws Exception {
        try (ProcessStorageLease first = ProcessStorageLease.tryAcquire(temporary.resolve("first")).orElseThrow();
             ProcessStorageLease second = ProcessStorageLease.tryAcquire(temporary.resolve("second")).orElseThrow()) {
            assertTrue(first.isHeld());
            assertTrue(second.isHeld());
        }
    }
}
