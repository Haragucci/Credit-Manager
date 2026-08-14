package op.creditmanager.client.storage;

import java.nio.file.Path;

public final class StorageLeaseChildMain {
    private StorageLeaseChildMain() { }

    public static void main(String[] args) throws Exception {
        try (ProcessStorageLease ignored = ProcessStorageLease.tryAcquire(Path.of(args[0])).orElseThrow()) {
            System.out.println("LEASE_ACQUIRED");
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
