package dev.gate.modules.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BoundedLruCacheTest {

    @Test
    void evictsLeastRecentlyUsedEntryBeyondMaxSize() {
        BoundedLruCache<String, Integer> cache = new BoundedLruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");      // touch "a" so "b" becomes the LRU entry
        cache.put("c", 3);   // evicts "b"

        assertEquals(1, cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals(3, cache.get("c"));
        assertEquals(2, cache.size());
    }

    @Test
    void putIfAbsentKeepsExistingValue() {
        BoundedLruCache<String, Integer> cache = new BoundedLruCache<>(10);
        assertNull(cache.putIfAbsent("k", 1));
        assertEquals(1, cache.putIfAbsent("k", 2));
        assertEquals(1, cache.get("k"));
    }

    @Test
    void expiredEntriesAreInvisible() throws Exception {
        BoundedLruCache<String, Integer> cache = new BoundedLruCache<>(10, 30);
        cache.put("k", 1);
        assertEquals(1, cache.get("k"));
        Thread.sleep(60);
        assertNull(cache.get("k"));
        // putIfAbsent may overwrite an expired entry
        assertNull(cache.putIfAbsent("k", 2));
        assertEquals(2, cache.get("k"));
    }
}
