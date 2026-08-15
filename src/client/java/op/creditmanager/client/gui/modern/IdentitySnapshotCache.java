package op.creditmanager.client.gui.modern;

import java.util.function.Supplier;

final class IdentitySnapshotCache<T> {
    private Object context;
    private T value;
    private boolean initialized;

    T get(Object nextContext, Supplier<T> loader) {
        if (!initialized || context != nextContext) {
            value = loader.get();
            context = nextContext;
            initialized = true;
        }
        return value;
    }

    void invalidate() {
        context = null;
        value = null;
        initialized = false;
    }
}
