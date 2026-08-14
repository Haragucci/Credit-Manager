package op.creditmanager.client.gui.modern.recovery;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryActionControllerTest {
    @Test
    void doubleSubmissionAndConcurrentActionAreRejected() {
        RecoveryActionController controller = new RecoveryActionController();
        controller.reopen();
        List<Runnable> work = new ArrayList<>();
        AtomicInteger operations = new AtomicInteger();

        assertTrue(controller.start(RecoveryActionController.Action.RESTORE, () -> {
            operations.incrementAndGet();
            return true;
        }, work::add, Runnable::run, result -> { }));
        assertFalse(controller.start(RecoveryActionController.Action.REPAIR, () -> true,
                work::add, Runnable::run, result -> { }));

        work.getFirst().run();
        assertEquals(1, operations.get());
        assertEquals(RecoveryActionController.Status.SUCCESS, controller.state().status());
    }

    @Test
    void closingSuppressesPublicationWithoutCancellingCriticalOperation() {
        RecoveryActionController controller = new RecoveryActionController();
        controller.reopen();
        List<Runnable> work = new ArrayList<>();
        List<Runnable> publications = new ArrayList<>();
        AtomicInteger operations = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();

        controller.start(RecoveryActionController.Action.SNAPSHOT, () -> {
            operations.incrementAndGet();
            return true;
        }, work::add, publications::add, result -> completions.incrementAndGet());
        controller.close();
        controller.reopen();
        assertFalse(controller.start(RecoveryActionController.Action.REPAIR, () -> true,
                work::add, publications::add, result -> completions.incrementAndGet()));
        work.getFirst().run();
        publications.forEach(Runnable::run);

        assertEquals(1, operations.get());
        assertEquals(0, completions.get());
        assertFalse(controller.isRunning());
    }

    @Test
    void failedOperationPublishesDeterministicErrorState() {
        RecoveryActionController controller = new RecoveryActionController();
        controller.reopen();
        List<RecoveryActionController.Result> results = new ArrayList<>();

        controller.start(RecoveryActionController.Action.REPAIR, () -> {
            throw new IllegalStateException("injected");
        }, Runnable::run, Runnable::run, results::add);

        assertEquals(RecoveryActionController.Status.ERROR, controller.state().status());
        assertEquals(1, results.size());
        assertFalse(results.getFirst().success());
    }

    @Test
    void rejectedPublicationReleasesRunningAndAllowsNextAction() {
        RecoveryActionController controller = new RecoveryActionController();
        controller.reopen();
        AtomicInteger operations = new AtomicInteger();

        assertTrue(controller.start(RecoveryActionController.Action.SNAPSHOT, () -> {
            operations.incrementAndGet();
            return true;
        }, Runnable::run, runnable -> { throw new IllegalStateException("injected"); }, result -> { }));

        assertFalse(controller.isRunning());
        assertEquals(RecoveryActionController.Status.ERROR, controller.state().status());
        assertTrue(controller.start(RecoveryActionController.Action.RECHECK, () -> {
            operations.incrementAndGet();
            return true;
        }, Runnable::run, Runnable::run, result -> { }));
        assertEquals(2, operations.get());
        assertFalse(controller.isRunning());
        assertEquals(RecoveryActionController.Status.SUCCESS, controller.state().status());
    }
}
