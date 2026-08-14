package op.creditmanager.client.gui.modern.stats;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsViewModelTest {
    @Test
    void sameLoadedKeyUsesCacheWithoutSecondQuery() {
        StatisticsViewModel model = new StatisticsViewModel();
        model.reopen();
        StatisticsViewKey key = key(1L);
        AtomicInteger queries = new AtomicInteger();

        model.request(key, () -> statistics(queries.incrementAndGet()), Runnable::run, Runnable::run);
        model.request(key, () -> statistics(queries.incrementAndGet()), Runnable::run, Runnable::run);

        assertEquals(1, queries.get());
        assertEquals(StatisticsViewState.Status.LOADED, model.state().status());
        assertFalse(model.needsRequest(key));
        assertTrue(model.needsRequest(key(2L)));
    }

    @Test
    void closeRejectsPendingPublication() {
        StatisticsViewModel model = new StatisticsViewModel();
        model.reopen();
        List<Runnable> work = new ArrayList<>();
        List<Runnable> publications = new ArrayList<>();

        model.request(key(1L), () -> statistics(1), work::add, publications::add);
        model.close();
        work.forEach(Runnable::run);
        publications.forEach(Runnable::run);

        assertEquals(StatisticsViewState.Status.LOADING, model.state().status());
    }

    @Test
    void staleQueryCannotOverwriteNewerRevision() {
        StatisticsViewModel model = new StatisticsViewModel();
        model.reopen();
        List<Runnable> work = new ArrayList<>();
        List<Runnable> publications = new ArrayList<>();
        Executor queued = work::add;

        model.request(key(1L), () -> statistics(1), queued, publications::add);
        model.request(key(2L), () -> statistics(2), queued, publications::add);
        work.get(1).run();
        work.get(0).run();
        publications.forEach(Runnable::run);

        assertEquals(BigInteger.valueOf(2), model.state().statistics().openClaimsMinor());
    }

    @Test
    void failureBecomesErrorAndRetryCreatesNewTicket() {
        StatisticsViewModel model = new StatisticsViewModel();
        model.reopen();
        StatisticsViewKey key = key(1L);
        AtomicInteger attempts = new AtomicInteger();

        model.request(key, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("injected");
        }, Runnable::run, Runnable::run);
        assertEquals(StatisticsViewState.Status.ERROR, model.state().status());

        model.retry(key, () -> statistics(attempts.incrementAndGet()), Runnable::run, Runnable::run);
        assertEquals(2, attempts.get());
        assertEquals(StatisticsViewState.Status.LOADED, model.state().status());
    }

    @Test
    void revisionChangeBeforePublicationDiscardsResultAndAllowsReload() {
        StatisticsViewModel model = new StatisticsViewModel();
        model.reopen();
        StatisticsViewKey key = key(1L);
        AtomicBoolean current = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger();

        model.request(key, () -> statistics(attempts.incrementAndGet()), current::get, Runnable::run, Runnable::run);

        assertEquals(StatisticsViewState.Status.LOADING, model.state().status());
        current.set(true);
        model.request(key, () -> statistics(attempts.incrementAndGet()), current::get, Runnable::run, Runnable::run);

        assertEquals(2, attempts.get());
        assertEquals(StatisticsViewState.Status.LOADED, model.state().status());
        assertEquals(BigInteger.valueOf(2L), model.state().statistics().openClaimsMinor());
    }

    @Test
    void rejectedPublicationDoesNotLeaveRequestStuck() {
        StatisticsViewModel model = new StatisticsViewModel();
        model.reopen();
        StatisticsViewKey key = key(1L);
        AtomicInteger attempts = new AtomicInteger();

        model.request(key, () -> statistics(attempts.incrementAndGet()), Runnable::run,
                runnable -> { throw new IllegalStateException("injected"); });

        assertEquals(StatisticsViewState.Status.ERROR, model.state().status());
        model.retry(key, () -> statistics(attempts.incrementAndGet()), Runnable::run, Runnable::run);
        assertEquals(2, attempts.get());
        assertEquals(StatisticsViewState.Status.LOADED, model.state().status());
    }

    private StatisticsViewKey key(long revision) {
        return new StatisticsViewKey("player", 1, null, null, 0L, revision, revision);
    }

    private CreditStatistics statistics(int value) {
        BigInteger amount = BigInteger.valueOf(value);
        return new CreditStatistics(amount, BigInteger.ZERO, 1, 0, BigInteger.ZERO, BigInteger.ZERO,
                amount, BigInteger.ZERO, BigInteger.ZERO, List.of(), BigInteger.ZERO, BigInteger.ZERO,
                value, 0, 0);
    }
}
