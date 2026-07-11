package dev.gate.modules.auth;

import dev.gate.core.Context;
import dev.gate.core.Handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Before-filter that authenticates requests with an {@code X-API-Key} header.
 * Comparison is constant-time to avoid timing leaks.
 *
 * <p>Generalized from a version used in production behind Cloudflare for a
 * public festival API. Supports one full-access key and an optional read-only
 * key: read-only clients may issue GET requests (outside the admin prefix) plus
 * any explicitly allowlisted method/path pairs.</p>
 *
 * <pre>
 * gate.before(ApiKeyAuth.builder()
 *     .key(System.getenv("API_KEY"))              // required
 *     .readOnlyKey(System.getenv("READ_ONLY_KEY")) // optional
 *     .adminPrefix("/admin")                       // read-only keys are rejected here
 *     .exemptPath("/health")
 *     .allowReadOnly("POST", "/stars")             // writes allowed for read-only keys
 *     .build());
 * </pre>
 */
public final class ApiKeyAuth implements Handler {

    private final String header;
    private final byte[] keyBytes;
    private final byte[] readOnlyKeyBytes;
    private final String adminPrefix;
    private final Set<String> exemptPaths;
    private final Set<String> readOnlyAllowed; // "METHOD path" entries

    private ApiKeyAuth(Builder b) {
        this.header           = b.header;
        this.keyBytes         = b.key.getBytes(StandardCharsets.UTF_8);
        this.readOnlyKeyBytes = b.readOnlyKey != null ? b.readOnlyKey.getBytes(StandardCharsets.UTF_8) : null;
        this.adminPrefix      = b.adminPrefix;
        this.exemptPaths      = Set.copyOf(b.exemptPaths);
        this.readOnlyAllowed  = Set.copyOf(b.readOnlyAllowed);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void handle(Context ctx) {
        if (exemptPaths.contains(ctx.path())) return;
        // Browsers do not send credentials in CORS preflights
        if ("OPTIONS".equals(ctx.method())) return;

        String provided = ctx.requestHeader(header);
        if (provided == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
            return;
        }

        if (constantEquals(provided, keyBytes)) return;

        if (readOnlyKeyBytes != null && constantEquals(provided, readOnlyKeyBytes)) {
            if (adminPrefix != null && ctx.path().startsWith(adminPrefix)) {
                ctx.status(403).json(Map.of("error", "Forbidden: admin access requires admin key")).halt();
                return;
            }
            if (readOnlyAllowed.contains(ctx.method().toUpperCase() + " " + ctx.path())) {
                return;
            }
            if (!"GET".equalsIgnoreCase(ctx.method())) {
                ctx.status(403).json(Map.of("error", "Forbidden: read-only access")).halt();
            }
            return;
        }

        ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
    }

    private static boolean constantEquals(String a, byte[] b) {
        if (a == null || b == null) return false;
        // No early return on length mismatch — MessageDigest.isEqual is timing-safe
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b);
    }

    public static final class Builder {
        private String header = "X-API-Key";
        private String key;
        private String readOnlyKey;
        private String adminPrefix = "/admin";
        private final Set<String> exemptPaths = new LinkedHashSet<>();
        private final Set<String> readOnlyAllowed = new LinkedHashSet<>();

        private Builder() {}

        /** Header name carrying the key (default {@code X-API-Key}). */
        public Builder header(String name) { this.header = name; return this; }

        /** The full-access API key. Required and must be non-blank. */
        public Builder key(String value) { this.key = value; return this; }

        /** Optional read-only key. Pass {@code null} to disable the read-only tier. */
        public Builder readOnlyKey(String value) {
            this.readOnlyKey = (value == null || value.isBlank()) ? null : value;
            return this;
        }

        /** Path prefix always denied to read-only keys (default {@code /admin}; {@code null} disables). */
        public Builder adminPrefix(String prefix) { this.adminPrefix = prefix; return this; }

        /** Exact path that bypasses authentication entirely (e.g. {@code /health}). */
        public Builder exemptPath(String path) { this.exemptPaths.add(path); return this; }

        /** Allows a non-GET method/path pair for read-only keys (e.g. idempotent client writes). */
        public Builder allowReadOnly(String method, String path) {
            this.readOnlyAllowed.add(method.toUpperCase() + " " + path);
            return this;
        }

        public ApiKeyAuth build() {
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("ApiKeyAuth: key must be set (e.g. from the API_KEY environment variable)");
            }
            return new ApiKeyAuth(this);
        }
    }
}
