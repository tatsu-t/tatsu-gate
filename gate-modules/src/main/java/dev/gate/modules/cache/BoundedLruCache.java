package dev.gate.modules.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small thread-safe LRU cache with a maximum size and an optional per-entry TTL.
 *
 * <p>Backed by a synchronized access-ordered {@link LinkedHashMap}. Intended for
 * moderate-traffic middleware caches (IP-match results, JWT verification results,
 * request-ID dedup). Deliberately avoids Caffeine so GraalVM native-image builds
 * need no manual reflect-config entries.</p>
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class BoundedLruCache<K, V> {

    private record Timestamped<V>(V value, long writtenAtMillis) {}

    private final long ttlMillis; // <= 0 means no expiry
    private final LinkedHashMap<K, Timestamped<V>> map;

    /**
     * @param maxSize   maximum number of entries; least-recently-used entries are evicted
     * @param ttlMillis per-entry time-to-live in milliseconds; {@code 0} disables expiry
     */
    public BoundedLruCache(int maxSize, long ttlMillis) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be positive");
        this.ttlMillis = ttlMillis;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Timestamped<V>> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** Creates a size-bounded cache without expiry. */
    public BoundedLruCache(int maxSize) {
        this(maxSize, 0);
    }

    /** Returns the cached value, or {@code null} if absent or expired. */
    public synchronized V get(K key) {
        Timestamped<V> e = map.get(key);
        if (e == null) return null;
        if (ttlMillis > 0 && System.currentTimeMillis() - e.writtenAtMillis() >= ttlMillis) {
            map.remove(key);
            return null;
        }
        return e.value();
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Timestamped<>(value, System.currentTimeMillis()));
    }

    /**
     * Atomically inserts the value if the key is absent (or its entry expired).
     * Returns the previous live value, or {@code null} if the new value was stored.
     */
    public synchronized V putIfAbsent(K key, V value) {
        V existing = get(key); // also purges an expired entry
        if (existing != null) return existing;
        map.put(key, new Timestamped<>(value, System.currentTimeMillis()));
        return null;
    }

    public synchronized int size() {
        return map.size();
    }
}
