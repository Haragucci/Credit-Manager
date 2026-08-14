package op.creditmanager.client.storage.db;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

class RecoveryFileOps {
    void createDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
    }

    void copy(Path source, Path target, CopyOption... options) throws IOException {
        Files.copy(source, target, options);
    }

    void moveWithoutReplacing(Path source, Path target) throws IOException {
        createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    void moveReplacing(Path source, Path target) throws IOException {
        createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void writeString(Path target, String value, Charset charset) throws IOException {
        Files.writeString(target, value, charset);
    }

    void deleteIfExists(Path target) throws IOException {
        Files.deleteIfExists(target);
    }
}
