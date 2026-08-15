package op.creditmanager.client.core;

import op.creditmanager.client.core.service.MutationCommitResult;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class CreditManagerMutationExecutor {
    private static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final CreditManagerMutationExecutor INSTANCE =
            new CreditManagerMutationExecutor(DEFAULT_QUEUE_CAPACITY, "CreditManager-Mutation");

    private final ThreadPoolExecutor executor;
    private final Object lifecycle = new Object();
    private boolean accepting = true;

    CreditManagerMutationExecutor(int queueCapacity, String threadName) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity");
        String safeThreadName = threadName == null || threadName.isBlank()
                ? "CreditManager-Mutation" : threadName;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, safeThreadName);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static CreditManagerMutationExecutor getInstance() {
        return INSTANCE;
    }

    public <T> CompletableFuture<T> submit(CheckedSupplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (!accepting) {
                future.completeExceptionally(new MutationRejectedException("CreditManager wird beendet."));
                return future;
            }
            try {
                executor.execute(() -> run(operation, future));
            } catch (RejectedExecutionException exception) {
                future.completeExceptionally(new MutationRejectedException(
                        "Die Warteschlange für Datenänderungen ist ausgelastet.", exception));
            }
        }
        return future;
    }

    public <T> CompletableFuture<MutationOutcome<T>> submit(CreditManager manager,
                                                             CheckedSupplier<T> operation) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(operation, "operation");
        return submit(() -> {
            manager.consumeLastMutationCommit();
            try {
                T value = operation.get();
                return new MutationOutcome<>(value, manager.consumeLastMutationCommit());
            } catch (Exception | Error failure) {
                manager.consumeLastMutationCommit();
                throw failure;
            }
        });
    }

    public void stopAccepting() {
        synchronized (lifecycle) {
            if (!accepting) return;
            accepting = false;
            executor.shutdown();
        }
    }

    public boolean shutdownAndAwait(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        stopAccepting();
        long nanos = Math.max(0L, timeout.toNanos());
        try {
            return executor.awaitTermination(nanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isIdle() {
        return executor.getActiveCount() == 0 && executor.getQueue().isEmpty();
    }

    public boolean isAccepting() {
        synchronized (lifecycle) {
            return accepting;
        }
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    private <T> void run(CheckedSupplier<T> operation, CompletableFuture<T> future) {
        try {
            future.complete(operation.get());
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public record MutationOutcome<T>(T value, MutationCommitResult commitResult) { }

    public static final class MutationRejectedException extends RejectedExecutionException {
        public MutationRejectedException(String message) {
            super(message);
        }

        public MutationRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
