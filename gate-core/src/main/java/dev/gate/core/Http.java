package dev.gate.core;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Single {@link HttpClient} shared across the whole application.
 *
 * <p>Each {@code HttpClient.newHttpClient()} owns its own selector and connection
 * pool (resident threads under GraalVM Native Image). Per-request behaviour can be
 * tuned with {@code HttpRequest.timeout(...)}, so one shared client is enough and
 * saves threads and connections.</p>
 *
 * <p>{@link HttpClient} is thread-safe and may be used concurrently.</p>
 */
public final class Http {

    /** Default connect-timeout cap. Set per-request read timeouts with {@code HttpRequest.timeout}. */
    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Http() {}
}
