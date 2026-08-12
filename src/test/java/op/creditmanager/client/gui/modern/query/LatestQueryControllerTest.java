package op.creditmanager.client.gui.modern.query;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestQueryControllerTest {
    @Test
    void fasterReplacementIsTheOnlyResultThatCanBeApplied() {
        LatestQueryController<String, String> controller = new LatestQueryController<>();
        controller.reopen();
        CompletableFuture<String> slow = new CompletableFuture<>();
        LatestQueryController.Ticket<String, String> first = controller.replace("A", slow);
        CompletableFuture<String> fast = CompletableFuture.completedFuture("B");
        LatestQueryController.Ticket<String, String> second = controller.replace("B", fast);

        assertTrue(slow.isCancelled());
        assertFalse(controller.isCurrent(first, "A"));
        assertTrue(controller.isCurrent(second, "B"));
        assertFalse(controller.isCurrent(second, "A"));
    }

    @Test
    void closingCancelsPendingWorkAndRejectsLateCompletion() {
        LatestQueryController<String, String> controller = new LatestQueryController<>();
        controller.reopen();
        CompletableFuture<String> pending = new CompletableFuture<>();
        LatestQueryController.Ticket<String, String> ticket = controller.replace("query", pending);

        controller.close();

        assertTrue(pending.isCancelled());
        assertFalse(controller.isCurrent(ticket, "query"));
    }
}
