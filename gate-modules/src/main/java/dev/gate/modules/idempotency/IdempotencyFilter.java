package dev.gate.modules.idempotency;

import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Logger;
import dev.gate.modules.cache.BoundedLruCache;

import java.time.Duration;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * Idempotency guard for client mutations. As a before-filter it requires a
 * valid {@code X-Request-Id} UUID header on matching requests; the handler then
 * calls {@link #markSeenOrReject(String)} after its own auth checks pass and
 * responds 409 on duplicates.
 *
 * <pre>
 * IdempotencyFilter idem = new IdempotencyFilter((method, path) ->
 *     path.equals("/stars") && (method.equals("POST") || method.equals("DELETE")));
 * gate.before(idem);
 *
 * // in the handler, after authentication:
 * if (!idem.markSeenOrReject(ctx.requestHeader("X-Request-Id"))) {
 *     ctx.status(409).json(Map.of("error", "duplicate request"));
 *     return;
 * }
 * </pre>
 *
 * <p>The two-step split matters: the dedup cache must only be populated after
 * authentication, so unauthenticated callers cannot pollute it. The cache is
 * bounded (100k entries, 30 min TTL) to cap memory.</p>
 */
public final class IdempotencyFilter implements Handler {

    private static final Logger logger = new Logger(IdempotencyFilter.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final String header;
    private final BiPredicate<String, String> matcher; // (METHOD, path)
    private final BoundedLruCache<String, Boolean> seen;

    /** Uses the {@code X-Request-Id} header, a 100k-entry cache, and a 30-minute window. */
    public IdempotencyFilter(BiPredicate<String, String> methodPathMatcher) {
        this("X-Request-Id", methodPathMatcher, 100_000, Duration.ofMinutes(30));
    }

    /**
     * @param header            request header carrying the client-generated UUID
     * @param methodPathMatcher which (upper-case method, path) pairs require an ID
     * @param maxEntries        dedup cache bound
     * @param window            how long a request ID is remembered
     */
    public IdempotencyFilter(String header, BiPredicate<String, String> methodPathMatcher,
                             int maxEntries, Duration window) {
        this.header  = header;
        this.matcher = methodPathMatcher;
        this.seen    = new BoundedLruCache<>(maxEntries, window.toMillis());
    }

    @Override
    public void handle(Context ctx) {
        if (!matcher.test(ctx.method().toUpperCase(), ctx.path())) return;

        String requestId = ctx.requestHeader(header);
        if (requestId == null || !UUID_PATTERN.matcher(requestId).matches()) {
            ctx.status(400).json(Map.of("error", "Missing or invalid " + header)).halt();
        }
    }

    /**
     * Marks the request ID as seen; returns {@code false} for duplicates (the
     * caller should respond 409). Call only after authentication has passed so
     * unauthenticated requests cannot pollute the dedup cache.
     */
    public boolean markSeenOrReject(String requestId) {
        Boolean existing = seen.putIfAbsent(requestId, Boolean.TRUE);
        if (existing != null) {
            logger.warn("Duplicate {} rejected: {}", header, requestId.substring(0, 8));
            return false;
        }
        return true;
    }
}
