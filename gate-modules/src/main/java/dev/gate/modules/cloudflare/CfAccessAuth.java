package dev.gate.modules.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Http;
import dev.gate.core.Json;
import dev.gate.core.Logger;
import dev.gate.modules.cache.BoundedLruCache;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Before-filter that validates Cloudflare Access JWTs
 * ({@code CF-Access-Jwt-Assertion} header) for admin routes. Signature
 * verification uses only {@code java.security.Signature} + Jackson (no external
 * JWT library), which keeps GraalVM native-image builds free of extra
 * reflect-config.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Requests under a protected prefix (default {@code /admin}) require a valid
 *       JWT whose {@code email} claim is in {@code ADMIN_EMAILS}; the verified email
 *       is stored as the {@link #ATTR_VERIFIED_EMAIL} context attribute.</li>
 *   <li>Other requests carrying the header get opportunistic verification: on
 *       success the email attribute is set (useful for audit logs), on failure the
 *       request proceeds without it.</li>
 *   <li>{@code INTERNAL_SERVICE_KEY} (optional) enables server-to-server calls to
 *       protected routes via a constant-time-compared {@code X-Service-Key} header.</li>
 * </ul>
 *
 * <p>Environment variables: {@code CF_ACCESS_AUD}, {@code CF_ACCESS_TEAM_DOMAIN}
 * (both required unless {@code CF_ACCESS_DEV_DISABLE=true}), {@code ADMIN_EMAILS}
 * (comma-separated, required when enabled), {@code INTERNAL_SERVICE_KEY} (optional).
 * When disabled, protected routes answer 503 instead of silently opening up.</p>
 */
public class CfAccessAuth implements Handler {

    /** Context attribute key holding the verified email address. */
    public static final String ATTR_VERIFIED_EMAIL = "cf_verified_email";

    private static final Logger logger = new Logger(CfAccessAuth.class);
    private static final ObjectMapper mapper = Json.MAPPER;
    private static final HttpClient httpClient = Http.CLIENT;

    private static final Duration JWKS_CACHE_TTL = Duration.ofHours(1);
    private static final long CLOCK_SKEW_LEEWAY_SECS = 30L;
    private static final int VERIFICATION_CACHE_MAX = 10_000;

    private record VerificationResult(String email, long expiresAtEpochSec) {}

    // Token → result cache; entries carry their own expiry (JWT exp, or short negative TTL)
    private final BoundedLruCache<String, VerificationResult> verificationCache =
            new BoundedLruCache<>(VERIFICATION_CACHE_MAX);

    private final AtomicReference<ConcurrentHashMap<String, PublicKey>> keyCacheRef
            = new AtomicReference<>(new ConcurrentHashMap<>());

    private volatile Instant keysCachedAt = Instant.EPOCH;
    private final ReentrantLock jwksLock = new ReentrantLock();
    private final String audience;
    private final String teamDomain;
    private final String certsUrl;
    private final boolean enabled;
    private final Set<String> adminEmails;
    private final byte[] internalServiceKeyBytes;
    private final List<String> protectedPrefixes;
    private final List<String> exemptPaths;

    public CfAccessAuth() {
        this(List.of("/admin"), List.of("/health"));
    }

    /**
     * @param protectedPrefixes path prefixes that require an admin-listed verified email
     * @param exemptPaths       exact paths that bypass the filter entirely
     */
    public CfAccessAuth(List<String> protectedPrefixes, List<String> exemptPaths) {
        this.protectedPrefixes = List.copyOf(protectedPrefixes);
        this.exemptPaths       = List.copyOf(exemptPaths);

        String aud     = System.getenv("CF_ACCESS_AUD");
        String domain  = System.getenv("CF_ACCESS_TEAM_DOMAIN");
        String devFlag = System.getenv("CF_ACCESS_DEV_DISABLE"); // development only — never in production
        String admins  = System.getenv("ADMIN_EMAILS");
        this.adminEmails = (admins != null && !admins.isBlank())
            ? Arrays.stream(admins.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet())
            : Set.of();

        String sk = System.getenv("INTERNAL_SERVICE_KEY");
        this.internalServiceKeyBytes = (sk != null && !sk.isBlank())
            ? sk.getBytes(StandardCharsets.UTF_8)
            : null;
        if (internalServiceKeyBytes != null) {
            logger.info("CfAccessAuth: internal service key enabled (X-Service-Key)");
        }

        if (aud == null || aud.isBlank() || domain == null || domain.isBlank()) {
            if (!"true".equalsIgnoreCase(devFlag)) {
                throw new IllegalStateException(
                    "CfAccessAuth: CF_ACCESS_AUD and CF_ACCESS_TEAM_DOMAIN must be set. " +
                    "To disable CF Access JWT validation in development, set CF_ACCESS_DEV_DISABLE=true.");
            }
            logger.warn("CfAccessAuth: JWT validation DISABLED (CF_ACCESS_DEV_DISABLE=true)");
            this.audience   = null;
            this.teamDomain = null;
            this.certsUrl   = null;
            this.enabled    = false;
        } else {
            this.audience = aud.strip();
            String d = domain.strip();
            if (!d.contains(".")) {
                d = d + ".cloudflareaccess.com";
            }
            this.teamDomain = "https://" + d;
            this.certsUrl   = "https://" + d + "/cdn-cgi/access/certs";
            this.enabled    = true;
            logger.info("CfAccessAuth enabled. Audience={} Certs={} AdminEmails={}", audience, certsUrl, adminEmails.size());
            if (this.adminEmails.isEmpty()) {
                throw new IllegalStateException(
                    "CfAccessAuth: ADMIN_EMAILS must be set when CF Access is enabled. " +
                    "An empty list would grant admin access to every authenticated user.");
            }
        }
    }

    /** Whether the given email is on the admin allowlist. */
    public boolean isAdmin(String email) {
        if (email == null || email.isBlank()) return false;
        return adminEmails.contains(email.toLowerCase());
    }

    /** Warms the JWKS cache at startup so the first admin request does not pay the fetch. */
    public void prefetchJwks() {
        if (!enabled) return;
        try {
            jwksLock.lock();
            try {
                refreshKeysLocked();
            } finally {
                jwksLock.unlock();
            }
            logger.info("JWKS prefetch complete ({} keys cached)", keyCacheRef.get().size());
        } catch (Exception e) {
            logger.warn("JWKS prefetch failed (will retry on first request): {}", e.getMessage());
        }
    }

    private boolean isProtected(String path) {
        for (String prefix : protectedPrefixes) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public void handle(Context ctx) {
        if (exemptPaths.contains(ctx.path())) return;
        if ("OPTIONS".equals(ctx.method())) return;

        if (!enabled) {
            if (isProtected(ctx.path())) {
                ctx.status(503).json(Map.of("error",
                    "Admin access unavailable: CF Access is disabled")).halt();
            }
            return;
        }

        String token = ctx.requestHeader("CF-Access-Jwt-Assertion");

        if (isProtected(ctx.path())) {
            // Server-to-server (M2M) auth: skip JWT verification when the service key matches
            if (internalServiceKeyBytes != null) {
                String provided = ctx.requestHeader("X-Service-Key");
                if (provided != null && MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8), internalServiceKeyBytes)) {
                    ctx.setAttribute(ATTR_VERIFIED_EMAIL, "service@internal");
                    return;
                }
            }
            if (token == null || token.isBlank()) {
                ctx.status(401).json(Map.of("error", "Missing CF-Access-Jwt-Assertion header")).halt();
                return;
            }
            try {
                String email = verifyAndExtractEmailCached(token);
                if (!adminEmails.contains(email.toLowerCase())) {
                    logger.warn("Admin access denied for email={}", email);
                    ctx.status(403).json(Map.of("error", "Forbidden: admin access required")).halt();
                    return;
                }
                ctx.setAttribute(ATTR_VERIFIED_EMAIL, email);
            } catch (Exception e) {
                logger.warn("CF Access JWT validation failed: {}", e.getMessage());
                ctx.status(401).json(Map.of("error", "Invalid or expired Cloudflare Access token")).halt();
            }
        } else if (token != null && !token.isBlank()) {
            // Non-protected route with a token: opportunistically extract the email
            // for audit purposes. Access control stays with the other filters —
            // handlers may require the attribute themselves if they need it.
            try {
                String email = verifyAndExtractEmailCached(token);
                ctx.setAttribute(ATTR_VERIFIED_EMAIL, email);
            } catch (Exception e) {
                logger.debug("CF Access JWT opportunistic extraction failed: {}", e.getMessage());
            }
        }
    }

    // Verification-result cache to keep RSA verification off the hot path
    private String verifyAndExtractEmailCached(String token) throws Exception {
        long now = Instant.now().getEpochSecond();
        VerificationResult cached = verificationCache.get(token);
        if (cached != null && now < cached.expiresAtEpochSec()) {
            if (cached.email() != null) return cached.email();
            throw new SecurityException("JWT previously rejected (cached)");
        }

        try {
            String[] parts = token.split("\\.");
            long exp = 0L;
            if (parts.length == 3) {
                try {
                    JsonNode payload = mapper.readTree(decodeBase64Url(parts[1]));
                    exp = payload.path("exp").asLong(0);
                } catch (Exception ignored) {}
            }
            String email = verifyAndExtractEmail(token);
            long cacheExp = exp > 0 ? exp : (now + 300);
            verificationCache.put(token, new VerificationResult(email, cacheExp));
            return email;
        } catch (SecurityException | IllegalArgumentException e) {
            // Only deterministic signature/claim failures are negative-cached
            verificationCache.put(token, new VerificationResult(null, now + 60));
            throw e;
        }
        // Transient infrastructure failures (e.g. JWKS fetch) are not cached, so a
        // temporary outage cannot lock out a valid token for up to 60 seconds.
    }

    private String verifyAndExtractEmail(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("JWT must have 3 parts");

        String headerJson  = decodeBase64Url(parts[0]);
        String payloadJson = decodeBase64Url(parts[1]);
        byte[] sigBytes    = java.util.Base64.getUrlDecoder().decode(parts[2]);

        JsonNode header  = mapper.readTree(headerJson);
        JsonNode payload = mapper.readTree(payloadJson);

        String kid = header.path("kid").asText();
        String alg = header.path("alg").asText();
        if (!"RS256".equals(alg)) throw new IllegalArgumentException("Unsupported algorithm: " + alg);

        PublicKey key = getPublicKey(kid);
        byte[] signedData = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(signedData);
        if (!sig.verify(sigBytes)) throw new SecurityException("JWT signature verification failed");

        // Time claims
        long now = Instant.now().getEpochSecond();
        long exp = payload.path("exp").asLong(0);
        long iat = payload.path("iat").asLong(0);
        if (exp <= 0) throw new SecurityException("JWT missing required exp claim");
        if (now > exp + CLOCK_SKEW_LEEWAY_SECS) throw new SecurityException("JWT has expired (exp=" + exp + ")");
        if (iat > 0 && now - iat > 86400) throw new SecurityException("JWT iat too old (>24h)");
        long nbf = payload.path("nbf").asLong(0);
        if (nbf > 0 && now + 60 < nbf) throw new SecurityException("JWT not yet valid (nbf=" + nbf + ")");

        // Issuer
        JsonNode issNode = payload.path("iss");
        String iss = issNode.isNull() || issNode.isMissingNode() ? "" : issNode.asText();
        if (!teamDomain.equals(iss)) throw new SecurityException("JWT issuer mismatch: " + iss);

        // Audience
        JsonNode audNode = payload.get("aud");
        boolean audMatched = false;
        if (audNode != null) {
            if (audNode.isArray()) {
                for (JsonNode a : audNode) {
                    if (audience.equals(a.asText())) { audMatched = true; break; }
                }
            } else {
                audMatched = audience.equals(audNode.asText());
            }
        }
        if (!audMatched) throw new SecurityException("JWT audience mismatch");

        JsonNode emailNode = payload.path("email");
        String email = emailNode.isNull() || emailNode.isMissingNode() ? null : emailNode.asText();
        if (email == null || email.isBlank()) throw new SecurityException("JWT missing email claim");
        return email;
    }

    // ── JWKS fetch / cache ─────────────────────────────────────────────────

    private PublicKey getPublicKey(String kid) throws Exception {
        ConcurrentHashMap<String, PublicKey> current = keyCacheRef.get();
        boolean cacheValid = !current.isEmpty() &&
                Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL));
        if (cacheValid) {
            PublicKey cached = current.get(kid);
            if (cached != null) return cached;
            // Fresh cache without this kid — refetching would return the same set
            throw new SecurityException("No JWK found for kid=" + kid);
        }

        jwksLock.lock();
        try {
            current = keyCacheRef.get();
            cacheValid = !current.isEmpty() &&
                    Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL));
            if (cacheValid) {
                PublicKey cached = current.get(kid);
                if (cached != null) return cached;
                throw new SecurityException("No JWK found for kid=" + kid);
            }

            refreshKeysLocked();

            PublicKey key = keyCacheRef.get().get(kid);
            if (key == null) throw new SecurityException("No JWK found for kid=" + kid);
            return key;
        } finally {
            jwksLock.unlock();
        }
    }

    private void refreshKeysLocked() throws Exception {
        logger.info("Refreshing JWKS from {}", certsUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(certsUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch JWKS: HTTP " + resp.statusCode());
        }

        ConcurrentHashMap<String, PublicKey> fresh = new ConcurrentHashMap<>();
        JsonNode jwks = mapper.readTree(resp.body());
        for (JsonNode jwk : jwks.path("keys")) {
            if (!"RSA".equals(jwk.path("kty").asText())) continue;
            String  k = jwk.path("kid").asText();
            byte[] n  = java.util.Base64.getUrlDecoder().decode(jwk.path("n").asText());
            byte[] e  = java.util.Base64.getUrlDecoder().decode(jwk.path("e").asText());
            RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e));
            PublicKey pubKey = KeyFactory.getInstance("RSA").generatePublic(spec);
            fresh.put(k, pubKey);
        }
        keyCacheRef.set(fresh);
        keysCachedAt = Instant.now();
    }

    private static String decodeBase64Url(String input) {
        return new String(java.util.Base64.getUrlDecoder().decode(input), StandardCharsets.UTF_8);
    }
}
