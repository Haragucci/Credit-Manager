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
}
