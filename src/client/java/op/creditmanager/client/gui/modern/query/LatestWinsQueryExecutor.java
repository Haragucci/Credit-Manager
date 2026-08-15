package op.creditmanager.client.gui.modern.query;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class LatestWinsQueryExecutor {
    private final Object lifecycle = new Object();
    private final ThreadPoolExecutor executor;
    private final Map<Object, QueryTask<?>> latestByOwner = new IdentityHashMap<>();
    private boolean accepting = true;

    LatestWinsQueryExecutor(int pendingCapacity, String threadName) {
        if (pendingCapacity < 1) throw new IllegalArgumentException("pendingCapacity");
        String safeName = threadName == null || threadName.isBlank() ? "CreditManager-Query" : threadName;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(pendingCapacity), runnable -> {
                    Thread thread = new Thread(runnable, safeName);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    <T> CompletableFuture<T> submitLatest(Object owner, Supplier<T> supplier) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(supplier, "supplier");
        QueryTask<T> task = new QueryTask<>(owner, supplier);
        synchronized (lifecycle) {
            if (!accepting) {
                task.reject(new RejectedExecutionException("CreditManager query executor is shut down"));
                return task.future;
            }
            QueryTask<?> previous = latestByOwner.put(owner, task);
            if (previous != null) {
                previous.cancel();
                executor.remove(previous);
            }
            while (true) {
                try {
                    executor.execute(task);
                    return task.future;
                } catch (RejectedExecutionException exception) {
                    if (executor.isShutdown()) {
                        latestByOwner.remove(owner, task);
                        task.reject(exception);
                        return task.future;
                    }
                    Runnable displaced = executor.getQueue().poll();
                    if (!(displaced instanceof QueryTask<?> displacedTask)) {
                        latestByOwner.remove(owner, task);
                        task.reject(exception);
                        return task.future;
                    }
                    displacedTask.cancel();
                    latestByOwner.remove(displacedTask.owner, displacedTask);
                }
            }
        }
    }

    void cancel(Object owner) {
        if (owner == null) return;
        synchronized (lifecycle) {
            QueryTask<?> task = latestByOwner.remove(owner);
            if (task == null) return;
            task.cancel();
            executor.remove(task);
        }
    }

    boolean shutdownAndAwait(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        synchronized (lifecycle) {
            accepting = false;
            for (QueryTask<?> task : latestByOwner.values()) {
                if (executor.remove(task)) task.cancel();
            }
            latestByOwner.entrySet().removeIf(entry -> entry.getValue().future.isCancelled());
            executor.shutdown();
        }
        try {
            if (executor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return true;
            }
            executor.shutdownNow().forEach(task -> {
                if (task instanceof QueryTask<?> queryTask) queryTask.cancel();
            });
            return executor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    int pendingCount() {
        return executor.getQueue().size();
    }

    boolean isAccepting() {
        synchronized (lifecycle) {
            return accepting;
        }
    }

    private final class QueryTask<T> implements Runnable {
        private final Object owner;
        private final Supplier<T> supplier;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private QueryTask(Object owner, Supplier<T> supplier) {
            this.owner = owner;
            this.supplier = supplier;
        }

        @Override
        public void run() {
            if (future.isCancelled()) return;
            try {
                future.complete(supplier.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                synchronized (lifecycle) {
                    latestByOwner.remove(owner, this);
                }
            }
        }

        private void cancel() {
            future.cancel(false);
        }

        private void reject(Throwable failure) {
            future.completeExceptionally(failure);
        }
    }
}
