package dev.gate.modules.cloudflare;

import dev.gate.core.Http;
import dev.gate.core.Json;
import dev.gate.core.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare cache-purge utility.
 *
 * <p>Lets you run long edge TTLs and purge affected URLs when data is written,
 * making freshness event-driven instead of TTL-bound. URL-level purge needs
 * absolute URLs, built from the {@code PUBLIC_BASE_URL} environment variable
 * (e.g. {@code https://example.com}); when unset, URL purge is disabled and only
 * {@code purge_everything} works.</p>
 *
 * <p>Configuration: {@code CF_API_TOKEN}, {@code CF_ZONE_ID}, {@code PUBLIC_BASE_URL}.</p>
 *
 * <p>Note: Cloudflare's purge-by-URL is exact-match. Entries cached with query
 * strings are not covered — configure a Cache Rule with a query-ignoring cache
 * key on the CF side.</p>
 */
public final class CfPurge {

    private static final Logger logger = new Logger(CfPurge.class);
    private static final HttpClient HTTP = Http.CLIENT;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String API_TOKEN = System.getenv("CF_API_TOKEN");
    private static final String ZONE_ID = System.getenv("CF_ZONE_ID");
    private static final String PUBLIC_BASE_URL = normalizeBaseUrl(System.getenv("PUBLIC_BASE_URL"));

    static {
        if (!isConfigured()) {
            logger.warn("CfPurge disabled: CF_API_TOKEN / CF_ZONE_ID not configured");
        } else if (PUBLIC_BASE_URL == null) {
            logger.warn("PUBLIC_BASE_URL not set — URL-level purge disabled (purge_everything only)");
        } else {
            logger.info("CfPurge enabled: base={}", PUBLIC_BASE_URL);
        }
    }

    private CfPurge() {}

    public static boolean isConfigured() {
        return notBlank(API_TOKEN) && notBlank(ZONE_ID);
    }

    /** Purges everything, synchronously — for admin endpoints that report the outcome. */
    public static boolean purgeEverythingSync() {
        if (!isConfigured()) {
            logger.warn("CF purge skipped: CF_API_TOKEN or CF_ZONE_ID not configured");
            return false;
        }
        try {
            HttpResponse<String> res = HTTP.send(
                    request("{\"purge_everything\":true}"), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                logger.info("CF cache purged (everything)");
                return true;
            }
            logger.warn("CF purge returned HTTP {}: {}", res.statusCode(), truncate(res.body()));
            return false;
        } catch (Exception e) {
            logger.warn("CF purge failed: {}", e.getMessage());
            return false;
        }
    }

    /** Purges everything, asynchronously — for write-triggered automatic cache syncs. */
    public static void purgeEverythingAsync() {
        if (!isConfigured()) return;
        sendAsync("{\"purge_everything\":true}", "everything");
    }

    /**
     * Purges the given paths URL-by-URL, asynchronously. When
     * {@code PUBLIC_BASE_URL} is unset this is a no-op (the edge expires
     * naturally via s-maxage).
     */
    public static void purgeUrlsAsync(String... paths) {
        if (!isConfigured() || PUBLIC_BASE_URL == null) return;
        List<String> urls = buildUrls(PUBLIC_BASE_URL, paths);
        if (urls.isEmpty()) return;
        try {
            sendAsync(buildFilesBody(urls), String.join(",", paths));
        } catch (Exception e) {
            logger.warn("CF purge body build failed: {}", e.getMessage());
        }
    }

    // ── Pure functions (unit-tested) ───────────────────────────────────────

    /** Normalizes the base URL: adds a scheme, strips trailing slashes; blank → null. */
    static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Builds the absolute purge URLs from base × paths. Null/blank paths are skipped. */
    static List<String> buildUrls(String base, String... paths) {
        List<String> urls = new ArrayList<>(paths.length);
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            urls.add(base + (p.startsWith("/") ? p : "/" + p));
        }
        return urls;
    }

    /** Builds the {@code {"files":[...]}} request body. */
    static String buildFilesBody(List<String> urls) throws Exception {
        return Json.MAPPER.writeValueAsString(Map.of("files", urls));
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private static void sendAsync(String body, String what) {
        HTTP.sendAsync(request(body), HttpResponse.BodyHandlers.ofString())
            .thenAccept(res -> {
                if (res.statusCode() == 200) {
                    logger.info("CF cache purged ({})", what);
                } else {
                    logger.warn("CF purge ({}) returned HTTP {}: {}",
                            what, res.statusCode(), truncate(res.body()));
                }
            })
            .exceptionally(e -> {
                logger.warn("CF purge ({}) failed: {}", what, e.getMessage());
                return null;
            });
    }

    private static HttpRequest request(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.cloudflare.com/client/v4/zones/" + ZONE_ID + "/purge_cache"))
                .header("Authorization", "Bearer " + API_TOKEN)
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
