package op.creditmanager.client.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiProcessStorageLeaseIntegrationTest {
    @TempDir Path temporary;

    @Test
    void secondJvmIsBlockedUntilOwnerIsHardKilled() throws Exception {
        Process child = start(StorageLeaseChildMain.class, temporary.toString());
        try {
            awaitLine(child, "LEASE_ACQUIRED", Duration.ofSeconds(10));
            assertTrue(ProcessStorageLease.tryAcquire(temporary).isEmpty());
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            try (ProcessStorageLease takeover = acquireEventually(temporary, Duration.ofSeconds(5))) {
                assertTrue(takeover.isHeld());
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static Process start(Class<?> mainClass, String... args) throws Exception {
        String classpathFile = System.getProperty("creditmanager.testRuntimeClasspathFile");
        if (classpathFile == null || classpathFile.isBlank()) throw new IllegalStateException("test runtime classpath file is unavailable");
        String classpath = java.nio.file.Files.readString(Path.of(classpathFile));
        Path argumentFile = java.nio.file.Files.createTempFile("creditmanager-child-jvm-", ".args");
        java.util.List<String> arguments = new java.util.ArrayList<>();
        arguments.add("-cp");
        arguments.add(quoted(classpath));
        arguments.add(quoted(mainClass.getName()));
        for (String value : args) arguments.add(quoted(value));
        java.nio.file.Files.write(argumentFile, arguments, java.nio.charset.StandardCharsets.UTF_8);
        argumentFile.toFile().deleteOnExit();
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        return new ProcessBuilder(java, "@" + argumentFile).redirectErrorStream(true).start();
    }

    public static void awaitLine(Process process, String expected, Duration timeout) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) if (line.contains(expected)) return line;
                }
                throw new IllegalStateException("child exited before " + expected + " with code " + process.exitValue());
            });
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private ProcessStorageLease acquireEventually(Path root, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var lease = ProcessStorageLease.tryAcquire(root);
            if (lease.isPresent()) return lease.get();
            Thread.sleep(25L);
        }
        throw new IllegalStateException("lease was not released by the operating system");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
