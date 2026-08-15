package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiCompletionGenerationTest {
    @Test
    void invalidationMakesPreviouslyCapturedCompletionStale() {
        UiCompletionGeneration generation = new UiCompletionGeneration();
        long first = generation.capture();

        assertTrue(generation.isCurrent(first));
        generation.invalidate();

        assertFalse(generation.isCurrent(first));
        assertTrue(generation.isCurrent(generation.capture()));
    }
}
