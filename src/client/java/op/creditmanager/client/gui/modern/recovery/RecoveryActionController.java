package op.creditmanager.client.gui.modern.recovery;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RecoveryActionController {
    private long sequence;
    private long activeTicket;
    private boolean disposed;
    private boolean running;
    private State state = State.idle();

    public synchronized void reopen() {
        disposed = false;
    }

    public boolean start(Action action, Supplier<Boolean> operation, Executor executor,
                         Consumer<Runnable> publication, Consumer<Result> completion) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(publication);
        Objects.requireNonNull(completion);
        long ticket;
        synchronized (this) {
            if (disposed || running) return false;
            running = true;
            ticket = ++sequence;
            activeTicket = ticket;
            state = State.running(action);
        }
        CompletableFuture.supplyAsync(operation, executor).whenComplete((success, error) -> {
            Result result = finishOperation(ticket, action, success, error);
            if (result == null) return;
            try {
                publication.accept(() -> publish(ticket, result, completion));
            } catch (RuntimeException publicationFailure) {
                failPublication(ticket, action);
            }
        });
        return true;
    }

    private synchronized Result finishOperation(long ticket, Action action, Boolean success, Throwable error) {
        if (ticket == activeTicket) {
            running = false;
            activeTicket = 0L;
        }
        if (disposed || ticket != sequence) return null;
        boolean completed = error == null && Boolean.TRUE.equals(success);
        return new Result(action, completed, error);
    }

    private void publish(long ticket, Result result, Consumer<Result> completion) {
        synchronized (this) {
            if (disposed || ticket != sequence) return;
            state = result.success() ? State.success(result.action()) : State.error(result.action());
        }
        completion.accept(result);
    }

    private synchronized void failPublication(long ticket, Action action) {
        if (disposed || ticket != sequence) return;
        state = State.error(action);
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void close() {
        disposed = true;
        sequence++;
        state = State.idle();
    }

    public enum Action { REPAIR, RECHECK, HEALTHY_BACKUP, SNAPSHOT, RESTORE, CREATE_EMPTY }
    public enum Status { IDLE, RUNNING, SUCCESS, ERROR }
    public record State(Status status, Action action) {
        public static State idle() { return new State(Status.IDLE, null); }
        public static State running(Action action) { return new State(Status.RUNNING, action); }
        public static State success(Action action) { return new State(Status.SUCCESS, action); }
        public static State error(Action action) { return new State(Status.ERROR, action); }
    }
    public record Result(Action action, boolean success, Throwable error) { }
}
