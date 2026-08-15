package op.creditmanager.client.util;

import java.util.OptionalLong;
import java.util.function.Supplier;

final class BalanceTickCache {
    private Object context;
    private long tick = Long.MIN_VALUE;
    private OptionalLong value = OptionalLong.empty();

    OptionalLong get(Object nextContext, long nextTick, Supplier<OptionalLong> loader) {
        if (context == nextContext && tick == nextTick) return value;
        OptionalLong loaded = loader.get();
        context = nextContext;
        tick = nextTick;
        value = loaded == null ? OptionalLong.empty() : loaded;
        return value;
    }

    void invalidate() {
        context = null;
        tick = Long.MIN_VALUE;
        value = OptionalLong.empty();
    }
}
