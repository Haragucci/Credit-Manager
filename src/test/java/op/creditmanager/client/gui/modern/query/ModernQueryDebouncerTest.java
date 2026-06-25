package op.creditmanager.client.gui.modern.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernQueryDebouncerTest {
    @Test
    void commitsOnlyTheLastStableInput() {
        ModernQueryDebouncer debouncer = new ModernQueryDebouncer(300L);
        assertTrue(debouncer.update("a", 0L));
        assertFalse(debouncer.ready(299L));
        assertTrue(debouncer.update("ab", 200L));
        assertFalse(debouncer.ready(499L));
        assertTrue(debouncer.ready(500L));
        assertFalse(debouncer.ready(800L));
    }
}
