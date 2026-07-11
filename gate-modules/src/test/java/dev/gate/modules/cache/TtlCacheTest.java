package dev.gate.modules.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TtlCache, driving a fake clock to simulate time passing.
 */
public class TtlCacheTest {

    private static final long TTL_MS = 1000L;

    private long fakeNow = 0L;

    private TtlCache<String> newCache() {
        return new TtlCache<>(TTL_MS, () -> fakeNow);
    }

    @Test
    void freshHitDoesNotCallLoaderAgain() throws Exception {
        TtlCache<String> cache = newCache();
        AtomicInteger calls = new AtomicInteger();

        String first  = cache.get("k", () -> { calls.incrementAndGet(); return "v1"; });
        fakeNow = TTL_MS - 1;  // still within TTL
        String second = cache.get("k", () -> { calls.incrementAndGet(); return "v2"; });

        assertEquals("v1", first);
        assertEquals("v1", second);
        assertEquals(1, calls.get());
    }

    @Test
    void expiredEntryTriggersReload() throws Exception {
        TtlCache<String> cache = newCache();
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", () -> { calls.incrementAndGet(); return "old"; });
        fakeNow = TTL_MS;  // exactly expired
        String reloaded = cache.get("k", () -> { calls.incrementAndGet(); return "new"; });

        assertEquals("new", reloaded);
        assertEquals(2, calls.get());
    }

    @Test
    void loaderFailureWithStaleEntryReturnsStale() throws Exception {
        TtlCache<String> cache = newCache();

        cache.get("k", () -> "stale-value");
        fakeNow = TTL_MS;  // expire it

        String result = cache.get("k", () -> { throw new RuntimeException("upstream down"); });

        assertEquals("stale-value", result);
    }

    @Test
    void loaderFailureWithNoEntryPropagatesException() {
        TtlCache<String> cache = newCache();

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> cache.get("k", () -> { throw new RuntimeException("no data"); }));

        assertEquals("no data", ex.getMessage());
    }
}
