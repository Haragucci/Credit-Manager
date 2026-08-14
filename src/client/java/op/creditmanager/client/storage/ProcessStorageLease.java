package op.creditmanager.client.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;

public final class ProcessStorageLease implements AutoCloseable {
    private final Path lockFile;
    private final FileChannel channel;
    private final FileLock lock;

    private ProcessStorageLease(Path lockFile, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.channel = channel;
        this.lock = lock;
    }

    public static Optional<ProcessStorageLease> tryAcquire(Path storageRoot) throws IOException {
        Path root = storageRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve(".creditmanager.lock");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            channel.close();
            return Optional.empty();
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        try {
            byte[] payload = ("pid=" + ProcessHandle.current().pid() + "\nacquiredAt=" + Instant.now() + '\n')
                    .getBytes(StandardCharsets.UTF_8);
            channel.truncate(0L);
            channel.position(0L);
            channel.write(ByteBuffer.wrap(payload));
            channel.force(true);
            return Optional.of(new ProcessStorageLease(file, channel, lock));
        } catch (IOException exception) {
            lock.release();
            channel.close();
            throw exception;
        }
    }

    public Path lockFile() {
        return lockFile;
    }

    public boolean isHeld() {
        return lock.isValid() && channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            if (lock.isValid()) lock.release();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }
}
