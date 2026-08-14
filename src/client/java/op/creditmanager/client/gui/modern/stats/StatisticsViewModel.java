package op.creditmanager.client.gui.modern.stats;

import op.creditmanager.client.gui.modern.query.LatestQueryController;

import java.math.BigInteger;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class StatisticsViewModel {
    private final LatestQueryController<StatisticsViewKey, CreditStatistics> queries = new LatestQueryController<>();
    private StatisticsViewState state = StatisticsViewState.loading();
    private StatisticsViewKey requestedKey;
    private StatisticsViewKey loadedKey;

    public synchronized void reopen() {
        queries.reopen();
    }

    public void request(StatisticsViewKey key, Supplier<CreditStatistics> loader, Executor executor,
                        Consumer<Runnable> publication) {
        request(key, loader, () -> true, executor, publication, false);
    }

    public void request(StatisticsViewKey key, Supplier<CreditStatistics> loader, BooleanSupplier stillCurrent,
                        Executor executor, Consumer<Runnable> publication) {
        request(key, loader, stillCurrent, executor, publication, false);
    }

    public void retry(StatisticsViewKey key, Supplier<CreditStatistics> loader, Executor executor,
                      Consumer<Runnable> publication) {
        request(key, loader, () -> true, executor, publication, true);
    }

    public void retry(StatisticsViewKey key, Supplier<CreditStatistics> loader, BooleanSupplier stillCurrent,
                      Executor executor, Consumer<Runnable> publication) {
        request(key, loader, stillCurrent, executor, publication, true);
    }

    private void request(StatisticsViewKey key, Supplier<CreditStatistics> loader, BooleanSupplier stillCurrent, Executor executor,
                         Consumer<Runnable> publication, boolean force) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(loader);
        Objects.requireNonNull(stillCurrent);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(publication);
        CompletableFuture<CreditStatistics> future;
        LatestQueryController.Ticket<StatisticsViewKey, CreditStatistics> ticket;
        synchronized (this) {
            if (!force && (key.equals(requestedKey) || key.equals(loadedKey))) return;
            future = CompletableFuture.supplyAsync(loader, executor);
            ticket = queries.replace(key, future);
            requestedKey = key;
            state = StatisticsViewState.loading();
        }
        future.whenComplete((statistics, error) -> {
            try {
                publication.accept(() -> complete(ticket, key, statistics, error, stillCurrent));
            } catch (RuntimeException publicationFailure) {
                failPublication(ticket, key);
            }
        });
    }

    private void complete(LatestQueryController.Ticket<StatisticsViewKey, CreditStatistics> ticket,
                          StatisticsViewKey key, CreditStatistics statistics, Throwable error,
                          BooleanSupplier stillCurrent) {
        boolean current;
        try {
            current = stillCurrent.getAsBoolean();
        } catch (RuntimeException exception) {
            current = false;
        }
        complete(ticket, key, statistics, error, current);
    }

    private synchronized void complete(LatestQueryController.Ticket<StatisticsViewKey, CreditStatistics> ticket,
                                       StatisticsViewKey key, CreditStatistics statistics, Throwable error,
                                       boolean stillCurrent) {
        if (!queries.isCurrent(ticket, key)) return;
        requestedKey = null;
        if (!stillCurrent) {
            queries.invalidate();
            loadedKey = null;
            state = StatisticsViewState.loading();
            return;
        }
        if (error != null || statistics == null) {
            loadedKey = null;
            state = StatisticsViewState.error();
            return;
        }
        loadedKey = key;
        state = isEmpty(statistics) ? StatisticsViewState.empty(statistics) : StatisticsViewState.loaded(statistics);
    }

    private synchronized void failPublication(LatestQueryController.Ticket<StatisticsViewKey, CreditStatistics> ticket,
                                              StatisticsViewKey key) {
        if (!queries.isCurrent(ticket, key)) return;
        requestedKey = null;
        loadedKey = null;
        state = StatisticsViewState.error();
    }

    public synchronized void invalidate(String message) {
        queries.invalidate();
        requestedKey = null;
        loadedKey = null;
        state = StatisticsViewState.invalid(message);
    }

    public synchronized StatisticsViewState state() {
        return state;
    }

    public synchronized boolean needsRequest(StatisticsViewKey key) {
        return key != null && !key.equals(requestedKey) && !key.equals(loadedKey);
    }

    public synchronized void close() {
        queries.close();
        requestedKey = null;
        loadedKey = null;
        state = StatisticsViewState.loading();
    }

    private boolean isEmpty(CreditStatistics statistics) {
        return statistics.actionCount() == 0
                && statistics.openClaimCount() == 0
                && statistics.openDebtCount() == 0
                && statistics.openClaimsMinor().equals(BigInteger.ZERO)
                && statistics.openDebtsMinor().equals(BigInteger.ZERO);
    }
}
