package op.creditmanager.client.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class BoundedQueryCache<K, V> {
    private final int maximumEntries;
    private final Map<K, V> entries;
    private long revision = Long.MIN_VALUE;
    private long hits;
    private long misses;

    public BoundedQueryCache(int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        this.maximumEntries = maximumEntries;
        this.entries = new LinkedHashMap<>(maximumEntries, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > BoundedQueryCache.this.maximumEntries;
            }
        };
    }

    public synchronized V getOrCompute(long currentRevision, K key, Supplier<V> loader) {
        if (revision != currentRevision) {
            entries.clear();
            revision = currentRevision;
        }
        V value = entries.get(key);
        if (value != null) {
            hits++;
            return value;
        }
        misses++;
        V loaded = loader.get();
        if (loaded != null) entries.put(key, loaded);
        return loaded;
    }

    public synchronized void clear() {
        entries.clear();
        revision = Long.MIN_VALUE;
    }

    public synchronized int size() { return entries.size(); }
    public synchronized CacheStats stats() { return new CacheStats(hits, misses, entries.size()); }

    public record CacheStats(long hits, long misses, int entries) { }
}
