package op.creditmanager.client.storage.db;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class BackupCheckpointService {
    static final long COALESCE_DELAY_MILLIS = 250L;
    static final long MAX_BACKUP_LAG_MILLIS = 15_000L;
    static final long MAX_BACKUP_LAG_REVISIONS = 5L;
    static final long MAX_UNPROTECTED_MILLIS = 300_000L;
    static final long MAX_UNPROTECTED_REVISIONS = 10L;
    private final Object monitor = new Object();
    private final ScheduledThreadPoolExecutor executor;
    private final CheckpointOperation operation;
    private final ProtectionListener listener;
    private final LongSupplier clock;
    private ScheduledFuture<?> scheduled;
    private long requestedRevision;
    private long latestLocalBackupRevision = -1L;
    private long latestMirrorBackupRevision = -1L;
    private long latestLocalBackupAt;
    private long latestMirrorBackupAt;
    private long firstUnprotectedAt;
    private int consecutiveFailures;
    private boolean mirrorEnabled;
    private boolean running;
    private boolean accepting = true;
    private boolean stopped;
    private ProtectionState state;

    BackupCheckpointService(CheckpointOperation operation, ProtectionListener listener) {
        this(operation, listener, System::currentTimeMillis);
    }

    BackupCheckpointService(CheckpointOperation operation, ProtectionListener listener, LongSupplier clock) {
        this.operation = Objects.requireNonNull(operation);
        this.listener = listener == null ? ignored -> { } : listener;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "creditmanager-backup-checkpoint");
            thread.setDaemon(true);
            return thread;
        };
        executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    void seed(long currentRevision, long backupRevision, long backupCreatedAt) {
        seed(currentRevision, backupRevision, backupCreatedAt, backupRevision, backupCreatedAt, true);
    }

    void seed(long currentRevision, long localRevision, long localCreatedAt,
              long mirrorRevision, long mirrorCreatedAt, boolean mirrorEnabled) {
        ProtectionState change;
        synchronized (monitor) {
            requestedRevision = Math.max(requestedRevision, currentRevision);
            latestLocalBackupRevision = Math.max(-1L, localRevision);
            latestMirrorBackupRevision = Math.max(-1L, mirrorRevision);
            latestLocalBackupAt = Math.max(0L, localCreatedAt);
            latestMirrorBackupAt = Math.max(0L, mirrorCreatedAt);
            this.mirrorEnabled = mirrorEnabled;
            updateUnprotectedStartLocked();
            change = transitionLocked();
        }
        notifyChange(change);
    }

    void request(long committedRevision) {
        ProtectionState change;
        synchronized (monitor) {
            if (!accepting || stopped) return;
            requestedRevision = Math.max(requestedRevision, committedRevision);
            updateUnprotectedStartLocked();
            change = transitionLocked();
            if (needsCheckpointLocked()) scheduleLocked(initialDelayLocked());
        }
        notifyChange(change);
    }

    Metrics metrics() {
        ProtectionState change;
        Metrics metrics;
        synchronized (monitor) {
            updateUnprotectedStartLocked();
            change = transitionLocked();
            long effectiveRevision = effectiveRevisionLocked();
            metrics = new Metrics(requestedRevision, latestLocalBackupRevision, latestMirrorBackupRevision,
                    Math.max(0L, requestedRevision - effectiveRevision), latestLocalBackupAt, latestMirrorBackupAt,
                    consecutiveFailures, running || scheduled != null,
                    state == null ? calculateStateLocked() : state, firstUnprotectedAt, mirrorEnabled);
        }
        notifyChange(change);
        return metrics;
    }

    boolean flushAndShutdown(Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        synchronized (monitor) {
            accepting = false;
            if (needsCheckpointLocked() && !running) {
                if (scheduled != null) scheduled.cancel(false);
                scheduled = null;
                scheduleLocked(0L);
            }
            while ((running || scheduled != null) && needsCheckpointLocked()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            stopped = true;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
        }
        executor.shutdown();
        long remaining = deadline - System.nanoTime();
        if (remaining > 0L) {
            try {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        Metrics current = metrics();
        return isIdle() && current.latestRecoveryPointRevision() >= current.currentRevision();
    }

    void stopNow() {
        synchronized (monitor) {
            accepting = false;
            stopped = true;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
            monitor.notifyAll();
        }
        executor.shutdown();
    }

    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        synchronized (monitor) {
            while (running || scheduled != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            return true;
        }
    }

    boolean isIdle() {
        synchronized (monitor) {
            return !running && scheduled == null;
        }
    }

    void acceptExternalResult(CheckpointResult result) {
        if (result == null) {
            return;
        }

        ProtectionState change;
        boolean firstRealFailure;

        synchronized (monitor) {
            boolean fullyProtected =
                    result.localSuccess()
                            && (!result.mirrorEnabled() || result.mirrorSuccess());

            applyResultLocked(result);

            firstRealFailure =
                    !fullyProtected
                            && consecutiveFailures == 1
                            && state == ProtectionState.DEGRADED;

            change = transitionLocked();

            if (!stopped && needsCheckpointLocked()) {
                scheduleLocked(COALESCE_DELAY_MILLIS);
            }

            monitor.notifyAll();
        }

        notifyChange(change, firstRealFailure);
    }

    private long initialDelayLocked() {
        if (requestedRevision - effectiveRevisionLocked() >= MAX_BACKUP_LAG_REVISIONS) return 0L;
        long latestAt = Math.max(latestLocalBackupAt, latestMirrorBackupAt);
        if (latestAt > 0L && clock.getAsLong() - latestAt >= MAX_BACKUP_LAG_MILLIS) return 0L;
        return COALESCE_DELAY_MILLIS;
    }

    private void scheduleLocked(long delayMillis) {
        if (stopped || running || scheduled != null) return;
        scheduled = executor.schedule(this::runCheckpoint, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void runCheckpoint() {
        synchronized (monitor) {
            scheduled = null;

            if (stopped || !needsCheckpointLocked()) {
                monitor.notifyAll();
                return;
            }

            running = true;
        }

        CheckpointResult result;

        try {
            result = operation.create();

            if (result == null) {
                result = CheckpointResult.failed(mirrorEnabled);
            }
        } catch (RuntimeException exception) {
            result = CheckpointResult.failed(mirrorEnabled);
        }

        ProtectionState change;
        boolean firstRealFailure;

        synchronized (monitor) {
            running = false;

            boolean fullyProtected =
                    result.localSuccess()
                            && (!result.mirrorEnabled() || result.mirrorSuccess());

            applyResultLocked(result);

            firstRealFailure =
                    !fullyProtected
                            && consecutiveFailures == 1
                            && state == ProtectionState.DEGRADED;

            change = transitionLocked();

            if (!stopped && needsCheckpointLocked()) {
                long retryDelay = fullyProtected
                        ? COALESCE_DELAY_MILLIS
                        : Math.min(
                        MAX_BACKUP_LAG_MILLIS,
                        1_000L << Math.min(
                                4,
                                Math.max(0, consecutiveFailures - 1)
                        )
                );

                scheduleLocked(retryDelay);
            }

            monitor.notifyAll();
        }

        notifyChange(change, firstRealFailure);
    }

    private void applyResultLocked(CheckpointResult result) {
        mirrorEnabled = result.mirrorEnabled();
        if (result.localSuccess()) {
            latestLocalBackupRevision = Math.max(latestLocalBackupRevision, result.revision());
            latestLocalBackupAt = Math.max(latestLocalBackupAt, result.createdAt());
        }
        if (result.mirrorSuccess()) {
            latestMirrorBackupRevision = Math.max(latestMirrorBackupRevision, result.revision());
            latestMirrorBackupAt = Math.max(latestMirrorBackupAt, result.createdAt());
        }
        if (result.localSuccess() && (!mirrorEnabled || result.mirrorSuccess())) consecutiveFailures = 0;
        else consecutiveFailures++;
        updateUnprotectedStartLocked();
    }

    private boolean needsCheckpointLocked() {
        return requestedRevision > latestLocalBackupRevision
                || mirrorEnabled && requestedRevision > latestMirrorBackupRevision;
    }

    private long effectiveRevisionLocked() {
        return Math.max(latestLocalBackupRevision, latestMirrorBackupRevision);
    }

    private void updateUnprotectedStartLocked() {
        if (effectiveRevisionLocked() >= requestedRevision) firstUnprotectedAt = 0L;
        else if (firstUnprotectedAt == 0L) firstUnprotectedAt = clock.getAsLong();
    }

    private ProtectionState transitionLocked() {
        ProtectionState next = calculateStateLocked();
        if (next == state) return null;
        state = next;
        return next;
    }

    private ProtectionState calculateStateLocked() {
        boolean localCurrent = latestLocalBackupRevision >= requestedRevision;
        boolean mirrorCurrent = mirrorEnabled && latestMirrorBackupRevision >= requestedRevision;

        if (localCurrent && mirrorCurrent) {
            return ProtectionState.HEALTHY;
        }

        if (localCurrent) {
            return ProtectionState.LOCAL_ONLY;
        }

        if (mirrorCurrent) {
            return ProtectionState.MIRROR_ONLY;
        }

        long lag = Math.max(0L, requestedRevision - effectiveRevisionLocked());
        long age = firstUnprotectedAt == 0L
                ? 0L
                : Math.max(0L, clock.getAsLong() - firstUnprotectedAt);

        return lag > MAX_UNPROTECTED_REVISIONS
                || age > MAX_UNPROTECTED_MILLIS
                ? ProtectionState.CRITICAL
                : ProtectionState.DEGRADED;
    }

    private void notifyChange(ProtectionState change) {
        notifyChange(change, false);
    }

    private void notifyChange(ProtectionState change, boolean forceDegradedNotification) {
        ProtectionState effectiveChange = change;

        if (effectiveChange == ProtectionState.DEGRADED
                && !forceDegradedNotification) {
            synchronized (monitor) {
                if (consecutiveFailures == 0) {
                    return;
                }
            }
        }

        if (effectiveChange == null && forceDegradedNotification) {
            effectiveChange = ProtectionState.DEGRADED;
        }

        if (effectiveChange != null) {
            listener.onProtectionChanged(effectiveChange);
        }
    }

    enum ProtectionState {
        HEALTHY,
        LOCAL_ONLY,
        MIRROR_ONLY,
        DEGRADED,
        CRITICAL;

        boolean writesAllowed() {
            return this != CRITICAL;
        }
    }

    @FunctionalInterface
    interface CheckpointOperation { CheckpointResult create(); }
    @FunctionalInterface
    interface ProtectionListener { void onProtectionChanged(ProtectionState state); }
    record CheckpointResult(boolean localSuccess, boolean mirrorSuccess, boolean mirrorEnabled,
                            long revision, long createdAt) {
        static CheckpointResult success(long revision, long createdAt) {
            return new CheckpointResult(true, true, true, Math.max(0L, revision), Math.max(0L, createdAt));
        }
        static CheckpointResult of(boolean localSuccess, boolean mirrorSuccess, boolean mirrorEnabled,
                                   long revision, long createdAt) {
            return new CheckpointResult(localSuccess, mirrorSuccess, mirrorEnabled,
                    Math.max(0L, revision), Math.max(0L, createdAt));
        }
        static CheckpointResult failed() { return failed(true); }
        static CheckpointResult failed(boolean mirrorEnabled) {
            return new CheckpointResult(false, false, mirrorEnabled, 0L, 0L);
        }
    }
    record Metrics(long currentRevision, long latestLocalBackupRevision, long latestMirrorBackupRevision,
                   long backupLagRevisions, long latestLocalBackupAt, long latestMirrorBackupAt,
                   int consecutiveBackupFailures, boolean checkpointPending, ProtectionState protectionState,
                   long firstUnprotectedAt, boolean mirrorEnabled) {
        long latestHealthyBackupRevision() { return latestRecoveryPointRevision(); }
        long latestHealthyBackupAt() { return Math.max(latestLocalBackupAt, latestMirrorBackupAt); }
        long latestRecoveryPointRevision() { return Math.max(latestLocalBackupRevision, latestMirrorBackupRevision); }
        boolean protectionDegraded() { return protectionState != ProtectionState.HEALTHY; }
    }
}
