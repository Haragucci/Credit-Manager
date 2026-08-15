package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentitySnapshotCacheTest {

    @Test
    void loadsOncePerResourceManagerIdentityAndAgainAfterInvalidation() {
        IdentitySnapshotCache<Boolean> cache = new IdentitySnapshotCache<>();
        AtomicInteger loads = new AtomicInteger();
        Object firstManager = new Object();
        Object secondManager = new Object();

        assertTrue(cache.get(firstManager, () -> loads.incrementAndGet() == 1));
        assertTrue(cache.get(firstManager, () -> false));
        assertFalse(cache.get(secondManager, () -> {
            loads.incrementAndGet();
            return false;
        }));

        cache.invalidate();

        assertTrue(cache.get(secondManager, () -> {
            loads.incrementAndGet();
            return true;
        }));
        assertEquals(3, loads.get());
    }
}
