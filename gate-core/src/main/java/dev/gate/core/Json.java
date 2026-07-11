package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Single {@link ObjectMapper} shared across the whole application.
 *
 * <p>{@code ObjectMapper} is thread-safe and caches its serializers/deserializers
 * internally. Creating one instance per class wastes memory (each instance warms
 * its own cache) with no CPU benefit — sharing a single instance improves cache
 * hit rates. The framework itself ({@link Context}) and all modules use this
 * instance.</p>
 *
 * <p>If a call site needs different settings, derive a copy with
 * {@code Json.MAPPER.copy()} — never mutate the shared instance at runtime.</p>
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}
}
