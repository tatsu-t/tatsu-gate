package dev.gate.modules.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for DiscordWebhook.shouldSend debounce logic. No network calls.
 */
public class DiscordWebhookDebounceTest {

    /**
     * Calling every second for 15 seconds allows exactly the calls at
     * t=0, t=5000 and t=10000 (DEBOUNCE_MS = 5000).
     */
    @Test
    void allowsExactlyThreeCallsAt1sIntervalsOver15s() {
        // Test-specific key avoids interference with other tests
        String key = "test-debounce-" + System.nanoTime();
        // Production 'now' is System.currentTimeMillis(), far from the initial 0L;
        // start from a realistic base (base=0 would make the first call collide
        // with the initial timestamp).
        long base = 1_000_000L;
        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            long now = base + (long) i * 1_000; // +0ms, +1000ms, ..., +14000ms
            if (DiscordWebhook.shouldSend(key, now)) {
                allowed++;
            }
        }
        assertEquals(3, allowed, "only t=0, t=5s and t=10s should be allowed at 1s intervals over 15s");
    }
}
