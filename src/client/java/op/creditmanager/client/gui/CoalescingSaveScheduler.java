package op.creditmanager.client.gui;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class CoalescingSaveScheduler {
    private final ScheduledExecutorService executor;
    private final long delayMillis;
    private final BooleanSupplier saver;
    private ScheduledFuture<?> pending;
    private boolean dirty;
    private boolean closed;

    CoalescingSaveScheduler(ScheduledExecutorService executor, long delayMillis, BooleanSupplier saver) {
        this.executor = executor;
        this.delayMillis = delayMillis;
        this.saver = saver;
    }

    synchronized void request() {
        dirty = true;
        if (closed) return;
        if (pending != null) pending.cancel(false);
        try {
            pending = executor.schedule(this::saveIfDirty, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    synchronized Future<Boolean> flushAsync() {
        if (pending != null) pending.cancel(false);
        pending = null;
        if (closed) return null;
        try {
            return executor.submit(() -> {
                saveIfDirty();
                synchronized (this) {
                    return !dirty;
                }
            });
        } catch (RejectedExecutionException ignored) {
            return null;
        }
    }

    synchronized void close() {
        closed = true;
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    private void saveIfDirty() {
        synchronized (this) {
            pending = null;
            if (!dirty) return;
            dirty = false;
        }
        boolean saved = false;
        try {
            saved = saver.getAsBoolean();
        } finally {
            if (!saved) {
                synchronized (this) {
                    dirty = true;
                }
            }
        }
    }
}
