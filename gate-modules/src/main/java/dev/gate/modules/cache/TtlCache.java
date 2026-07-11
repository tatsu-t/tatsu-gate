package dev.gate.modules.cache;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Simple in-memory cache with a TTL.
 *
 * <p>Design notes:
 * <ul>
 *   <li>The loader is never invoked while holding a lock. Under contention the
 *       same key may be loaded by several threads at once (racy double-load);
 *       this is accepted. Suited to low-frequency, side-effect-free loads such
 *       as admin-panel metrics.</li>
 *   <li>If the loader throws and a stale entry exists, the stale entry is
 *       returned (stale-on-error). With no stale entry the exception is
 *       rethrown.</li>
 *   <li>Deliberately avoids Caffeine so GraalVM native-image builds need no
 *       manual reflect-config entries.</li>
 * </ul>
 *
 * @param <V> cached value type
 */
public class TtlCache<V> {

    private final long          ttlMillis;
    private final LongSupplier  clock;
    private final ConcurrentHashMap<String, Entry<V>> store = new ConcurrentHashMap<>();

    private record Entry<V>(V value, long loadedAtMillis) {}

    /**
     * @param ttlMillis cache lifetime in milliseconds
     * @param clock     current-time supplier (replaceable in tests)
     */
    public TtlCache(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.clock     = clock;
    }

    /** Convenience constructor using the system clock. */
    public TtlCache(long ttlMillis) {
        this(ttlMillis, System::currentTimeMillis);
    }

    /**
     * Returns the fresh cached value if present, otherwise invokes {@code loader}
     * and stores the result.
     *
     * <p>If the loader throws: a stale entry is returned when one exists
     * (stale-on-error); otherwise the exception is rethrown.</p>
     */
    public V get(String key, Callable<V> loader) throws Exception {
        long now = clock.getAsLong();
        Entry<V> existing = store.get(key);
        if (existing != null && (now - existing.loadedAtMillis()) < ttlMillis) {
            return existing.value();
        }

        // Expired or absent: try to load (no lock — duplicate loads are tolerated)
        try {
            V value = loader.call();
            store.put(key, new Entry<>(value, clock.getAsLong()));
            return value;
        } catch (Exception e) {
            if (existing != null) {
                return existing.value();
            }
            throw e;
        }
    }
}
