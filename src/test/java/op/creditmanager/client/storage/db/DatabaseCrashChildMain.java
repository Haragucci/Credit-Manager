package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class DatabaseCrashChildMain {
    private DatabaseCrashChildMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path mirror = "-".equals(args[1]) ? null : Path.of(args[1]);
        UUID creditId = UUID.fromString(args[2]);
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configurePrimary(root, mirror);
            DatabaseCoordinator coordinator = new DatabaseCoordinator();
            coordinator.initialize();
            CreditEntry credit = new CreditEntry(creditId, "hard-kill-exact", "alice", "bob", 77_777L, null, "durable");
            if (!coordinator.commitCreditMutation(new DatabaseManager.CreditMutation(credit, List.of(), List.of(), List.of()))) {
                throw new IllegalStateException("commit failed");
            }
            if (mirror != null) {
                DatabaseManager.ManualBackupResult backup = coordinator.createHealthyBackupNow();
                if (!backup.localSuccess() || !backup.mirrorSuccess()) throw new IllegalStateException("backup failed");
                Files.writeString(mirror.resolve("backups").resolve("interrupted-copy.zip.tmp"), "partial");
                System.out.println("BACKUP_CONFIRMED");
            } else {
                System.out.println("COMMIT_CONFIRMED");
            }
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
