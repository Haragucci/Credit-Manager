package op.creditmanager.client.util;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BalanceTickCacheTest {

    @Test
    void scansAtMostOncePerContextAndTick() {
        BalanceTickCache cache = new BalanceTickCache();
        AtomicInteger scans = new AtomicInteger();
        Object world = new Object();

        for (int frame = 0; frame < 100; frame++) {
            assertEquals(42L, cache.get(world, 10L,
                    () -> OptionalLong.of(scans.incrementAndGet() == 1 ? 42L : -1L)).orElseThrow());
        }

        assertEquals(1, scans.get());
        assertEquals(2L, cache.get(world, 11L,
                () -> OptionalLong.of(scans.incrementAndGet())).orElseThrow());
        assertEquals(2, scans.get());
    }

    @Test
    void contextChangeEmptyResultAndExplicitInvalidationRefresh() {
        BalanceTickCache cache = new BalanceTickCache();
        AtomicInteger scans = new AtomicInteger();
        Object firstWorld = new Object();
        Object secondWorld = new Object();

        assertEquals(OptionalLong.empty(), cache.get(firstWorld, 5L, () -> {
            scans.incrementAndGet();
            return OptionalLong.empty();
        }));
        assertEquals(OptionalLong.empty(), cache.get(firstWorld, 5L, () -> {
            scans.incrementAndGet();
            return OptionalLong.of(1L);
        }));
        assertEquals(7L, cache.get(secondWorld, 5L, () -> {
            scans.incrementAndGet();
            return OptionalLong.of(7L);
        }).orElseThrow());

        cache.invalidate();

        assertEquals(8L, cache.get(secondWorld, 5L, () -> {
            scans.incrementAndGet();
            return OptionalLong.of(8L);
        }).orElseThrow());
        assertEquals(3, scans.get());
    }
}
