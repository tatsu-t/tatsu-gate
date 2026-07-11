package dev.gate.modules.notify;

import dev.gate.core.Http;
import dev.gate.core.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends error and admin-operation notifications to a Discord webhook,
 * fire-and-forget with per-key debouncing (bursts of the same error produce a
 * single message per 5-second window).
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code DISCORD_WEBHOOK_URL} — webhook URL; unset disables all sending</li>
 *   <li>{@code DISCORD_WEBHOOK_URL_DEBUG} — used instead when {@code RUNMODE=debug}</li>
 *   <li>{@code DISCORD_MENTION} — optional mention prefix for error messages
 *       (e.g. {@code <@123456789>} or {@code <@&roleId>})</li>
 *   <li>{@code K_REVISION} / {@code K_SERVICE} — used as the instance label (Cloud Run)</li>
 * </ul>
 */
public final class DiscordWebhook {
    private static final Logger     logger      = new Logger(DiscordWebhook.class);
    private static final boolean    IS_DEBUG    = "debug".equalsIgnoreCase(System.getenv("RUNMODE"));
    private static final String     WEBHOOK     = resolveWebhook();
    private static final String     MENTION     = System.getenv("DISCORD_MENTION");
    private static final String     INSTANCE    = Optional.ofNullable(System.getenv("K_REVISION"))
            .or(() -> Optional.ofNullable(System.getenv("K_SERVICE")))
            .orElse(IS_DEBUG ? "debug" : "local");
    private static final long       DEBOUNCE_MS = 5_000L;
    private static final HttpClient HTTP        = Http.CLIENT;
    private static final ConcurrentHashMap<String, AtomicLong> lastSent = new ConcurrentHashMap<>();

    private static String resolveWebhook() {
        if (IS_DEBUG) {
            String debugUrl = System.getenv("DISCORD_WEBHOOK_URL_DEBUG");
            if (debugUrl != null && !debugUrl.isBlank()) return debugUrl;
        }
        return System.getenv("DISCORD_WEBHOOK_URL");
    }

    private DiscordWebhook() {}

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    /**
     * Decides whether a message for this key may be sent now. Uses
     * compareAndSet instead of getAndSet so the timestamp does not advance
     * during the debounce window (a burst of identical errors lets only the
     * first one through). Package-private for tests.
     */
    static boolean shouldSend(String key, long now) {
        AtomicLong ts = lastSent.computeIfAbsent(key, k -> new AtomicLong(0L));
        long last = ts.get();
        if (now - last < DEBOUNCE_MS) return false;
        return ts.compareAndSet(last, now);
    }

    public static void sendError(String method, String path, int status, String message) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;

        String key = method + " " + path + " " + status;
        long now   = System.currentTimeMillis();
        if (!shouldSend(key, now)) return;

        int color = status >= 500 ? 15158332 : 16776960;
        String prefix = IS_DEBUG ? "[DEBUG] " : "";
        String mention = (MENTION != null && !MENTION.isBlank())
                ? "\"content\": \"" + esc(MENTION) + "\",\n                  " : "";
        String body = """
                {
                  %s"embeds": [{
                    "title": "%s%d  %s  %s",
                    "description": "%s",
                    "color": %d,
                    "footer": { "text": "instance: %s" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(mention, prefix, status, esc(method), esc(path),
                              esc(message != null ? message : "(no message)"),
                              color, esc(INSTANCE), Instant.now());
        post(body);
    }

    public static void sendAdminOp(String user, String action, String target, String detail) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;
        String desc = detail != null && !detail.isBlank() ? ", \"description\": \"" + esc(detail) + "\"" : "";
        String prefix = IS_DEBUG ? "[DEBUG]" : "";
        String body = """
                {
                  "embeds": [{
                    "title": "[ADMIN]%s %s  %s"%s,
                    "color": 3447003,
                    "footer": { "text": "by: %s  |  instance: %s" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(prefix, esc(action), esc(target), desc, esc(user != null ? user : "unknown"),
                              esc(INSTANCE), Instant.now());
        post(body);
    }

    private static void post(String body) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WEBHOOK))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .exceptionally(e -> { logger.warn("Discord webhook send failed: {}", e.getMessage()); return null; });
    }
}
