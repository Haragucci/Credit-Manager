package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationSubmissionGuardTest {

    @Test
    void blocksRepeatedSubmissionUntilReset() {
        MutationSubmissionGuard guard = new MutationSubmissionGuard();

        assertTrue(guard.tryBegin());
        assertFalse(guard.tryBegin());
        guard.reset();
        assertTrue(guard.tryBegin());
    }

    @Test
    void onlyMatchingCompletionTokenReleasesPendingSubmission() {
        MutationSubmissionGuard guard = new MutationSubmissionGuard();
        long first = guard.tryBeginToken();

        assertFalse(guard.complete(first + 1L));
        assertTrue(guard.isActive());
        assertFalse(guard.tryBegin());
        assertTrue(guard.complete(first));
        assertFalse(guard.isActive());
    }

    @Test
    void staleCompletionCannotReleaseANewerSubmission() {
        MutationSubmissionGuard guard = new MutationSubmissionGuard();
        long first = guard.tryBeginToken();
        assertTrue(guard.complete(first));
        long second = guard.tryBeginToken();

        assertFalse(guard.complete(first));
        assertTrue(guard.isActive());
        assertTrue(guard.complete(second));
    }
}
