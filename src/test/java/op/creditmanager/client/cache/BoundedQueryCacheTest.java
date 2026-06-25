package op.creditmanager.client.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedQueryCacheTest {
    @Test
    void invalidatesOnRevisionChangesAndBoundsEntries() {
        BoundedQueryCache<String, Integer> cache = new BoundedQueryCache<>(2);
        AtomicInteger loads = new AtomicInteger();

        assertEquals(1, cache.getOrCompute(1L, "first", loads::incrementAndGet));
        assertEquals(1, cache.getOrCompute(1L, "first", loads::incrementAndGet));
        assertEquals(2, cache.getOrCompute(1L, "second", loads::incrementAndGet));
        assertEquals(3, cache.getOrCompute(1L, "third", loads::incrementAndGet));
        assertEquals(2, cache.size());
        assertEquals(4, cache.getOrCompute(2L, "first", loads::incrementAndGet));
        assertEquals(1, cache.size());
    }
}
