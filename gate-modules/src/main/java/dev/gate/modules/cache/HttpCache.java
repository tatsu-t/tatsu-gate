package dev.gate.modules.cache;

import dev.gate.core.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/**
 * Helpers for serving cached JSON responses over HTTP: precomputed gzip
 * variants, ETag generation, and RFC 9110 conditional-request handling.
 */
public final class HttpCache {

    private HttpCache() {}

    /**
     * Precomputed cache entry (identity body / gzip body / ETag). Build it once
     * per refresh with {@link #entryOf(byte[])}, then serve requests zero-copy
     * with {@link #serveJson(Context, Entry, String)}.
     */
    public record Entry(byte[] json, byte[] jsonGzip, String etag) {}

    /** Builds a serving entry from JSON bytes (gzip compression and ETag computed once). */
    public static Entry entryOf(byte[] json) throws IOException {
        return new Entry(json, gzip(json), etag(json));
    }

    /**
     * Common serving path for cached JSON: ETag / If-None-Match 304 handling,
     * pre-compressed gzip selection based on Accept-Encoding, and Vary header.
     * Route all cached JSON endpoints through this method so the serving
     * behaviour stays in one place.
     */
    public static void serveJson(Context ctx, Entry entry, String cacheControl) {
        ctx.header("Cache-Control", cacheControl);
        ctx.header("ETag", entry.etag());
        // gzip and identity are different representations — CDNs/proxies must distinguish them
        ctx.header("Vary", "Accept-Encoding");
        if (etagMatches(ctx.requestHeader("If-None-Match"), entry.etag())) {
            ctx.status(304);
            return;
        }
        String ae = ctx.requestHeader("Accept-Encoding");
        // Tiny bodies can grow with the gzip header overhead — only use the
        // compressed representation when it is actually smaller
        if (ae != null && ae.contains("gzip") && entry.jsonGzip().length < entry.json().length) {
            ctx.header("Content-Encoding", "gzip").jsonBytes(entry.jsonGzip());
        } else {
            ctx.jsonBytes(entry.json());
        }
    }

    /**
     * RFC 9110-compliant If-None-Match matching: comma-separated lists, the
     * {@code *} wildcard, and weak ETags ({@code W/"..."}, compared weakly per
     * RFC 9110 §13.1.2).
     */
    static boolean etagMatches(String inm, String etag) {
        if (inm == null) return false;
        if (inm.equals(etag)) return true;            // fast path
        for (String part : inm.split(",")) {
            String t = part.trim();
            if (t.equals("*")) return true;
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals(etag)) return true;
        }
        return false;
    }

    /** CRC32-based weak ETag, returned quoted so it can be used as a header value directly. */
    public static String etag(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return "\"" + Long.toHexString(crc.getValue()) + "\"";
    }

    /** gzip compression, for cache layers that keep pre-compressed bytes to serve with Content-Encoding: gzip. */
    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(32, data.length / 3));
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }
}
