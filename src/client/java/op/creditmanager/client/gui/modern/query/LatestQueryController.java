package op.creditmanager.client.gui.modern.query;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class LatestQueryController<K, T> {
    private long sequence;
    private boolean disposed;
    private Ticket<K, T> current;

    public synchronized void reopen() {
        disposed = false;
    }

    public synchronized Ticket<K, T> replace(K key, CompletableFuture<T> future) {
        invalidate();
        disposed = false;
        current = new Ticket<>(sequence, key, Objects.requireNonNull(future));
        return current;
    }

    public synchronized boolean isCurrent(Ticket<K, T> ticket, K expectedKey) {
        return !disposed && ticket != null && ticket == current && ticket.sequence() == sequence
                && Objects.equals(ticket.key(), expectedKey);
    }

    public synchronized void invalidate() {
        sequence++;
        if (current != null) current.future().cancel(true);
        current = null;
    }

    public synchronized void close() {
        disposed = true;
        invalidate();
    }

    public record Ticket<K, T>(long sequence, K key, CompletableFuture<T> future) { }
}
