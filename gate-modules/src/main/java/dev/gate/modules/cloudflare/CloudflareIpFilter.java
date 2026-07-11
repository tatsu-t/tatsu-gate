package dev.gate.modules.cloudflare;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Logger;
import dev.gate.modules.cache.BoundedLruCache;

/**
 * Before-filter that only allows requests arriving through one of Cloudflare's
 * proxies, by validating the right-most non-private IP in {@code X-Forwarded-For}
 * (works on Azure and Cloud Run alike).
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code SKIP_CF_IP_CHECK=true} — disable the check (local development only)</li>
 *   <li>{@code ORIGIN_SHARED_SECRET} — if set, requests must instead carry a matching
 *       {@code X-Origin-Secret} header (inject it via a Cloudflare Transform/Origin
 *       Rule). This closes the gap where any third-party Cloudflare zone pointed at
 *       this origin would pass a pure IP-range check, and removes the XFF dependency.</li>
 * </ul>
 *
 * IP ranges from https://www.cloudflare.com/ips-v4 and /ips-v6.
 */
public class CloudflareIpFilter implements Handler {

    private static final Logger logger = new Logger(CloudflareIpFilter.class);

    // Cloudflare CIDR list (last updated: 2026-04-26)
    private static final String[] CF_CIDRS = {
        // IPv4 — https://www.cloudflare.com/ips-v4
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22",
        // IPv6 — https://www.cloudflare.com/ips-v6
        "2400:cb00::/32",
        "2606:4700::/32",
        "2803:f800::/32",
        "2405:b500::/32",
        "2405:8100::/32",
        "2a06:98c0::/29",
        "2c0f:f248::/32",
    };

    private record CidrBlock(InetAddress network, int prefix) {}

    private static final String ORIGIN_SECRET_HEADER = "X-Origin-Secret";
    private static final int IP_CACHE_MAX = 50_000;

    private final List<CidrBlock> blocks;
    private final boolean skipCheck;
    private final byte[] originSecret;
    private final List<String> exemptPaths;
    private final BoundedLruCache<String, Boolean> ipMatchCache = new BoundedLruCache<>(IP_CACHE_MAX);

    public CloudflareIpFilter() {
        this(List.of("/health"));
    }

    /** @param exemptPaths exact paths that bypass the filter (e.g. health checks) */
    public CloudflareIpFilter(List<String> exemptPaths) {
        this.exemptPaths = List.copyOf(exemptPaths);
        this.skipCheck = "true".equalsIgnoreCase(System.getenv("SKIP_CF_IP_CHECK"));
        if (skipCheck) {
            logger.warn("SKIP_CF_IP_CHECK=true — Cloudflare IP check disabled (development only)");
        }
        String secret = System.getenv("ORIGIN_SHARED_SECRET");
        this.originSecret = (secret != null && !secret.isBlank())
                ? secret.getBytes(StandardCharsets.UTF_8) : null;
        if (originSecret != null) {
            logger.info("Origin shared-secret auth enabled (header={})", ORIGIN_SECRET_HEADER);
        } else if (!skipCheck) {
            logger.warn("ORIGIN_SHARED_SECRET not set — origin protection relies on Cloudflare IP ranges only."
                + " Injecting a secret header at the CF edge is recommended to block requests routed"
                + " through third-party Cloudflare zones.");
        }
        this.blocks = buildBlocks();
        logger.info("CloudflareIpFilter initialized: cidrBlocks={}", blocks.size());
    }

    @Override
    public void handle(Context ctx) {
        if (skipCheck) return;
        if (exemptPaths.contains(ctx.path())) return;

        // Shared-secret origin auth (only when ORIGIN_SHARED_SECRET is set).
        // A matching secret proves the request passed through our own CF zone,
        // so the IP check becomes unnecessary.
        if (originSecret != null) {
            String provided = ctx.requestHeader(ORIGIN_SECRET_HEADER);
            if (provided == null
                    || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), originSecret)) {
                logger.warn("Request rejected: missing/invalid origin secret. path={}", ctx.path());
                ctx.status(403).json(Map.of("error", "Forbidden")).halt();
            }
            return;
        }

        String candidateIp = resolveCloudflareIp(ctx);
        if (candidateIp == null || !isCloudflareIp(candidateIp)) {
            String xff = ctx.requestHeader("X-Forwarded-For");
            logger.warn("Request rejected: not from Cloudflare. candidate={} XFF={} path={}",
                    candidateIp, xff, ctx.path());
            ctx.status(403).json(Map.of("error", "Forbidden")).halt();
        }
    }

    // The right-most non-private IP in XFF must be a Cloudflare IP (Azure / Cloud Run alike)
    private String resolveCloudflareIp(Context ctx) {
        String xff = ctx.requestHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (!ip.isEmpty() && !isPrivateOrLoopback(ip)) {
                    return ip;
                }
            }
        }
        return null;
    }

    private boolean isCloudflareIp(String ipStr) {
        Boolean cached = ipMatchCache.get(ipStr);
        if (cached != null) return cached;
        boolean match = computeCloudflareMatch(ipStr);
        ipMatchCache.put(ipStr, match);
        return match;
    }

    private boolean computeCloudflareMatch(String ipStr) {
        InetAddress addr;
        try {
            addr = InetAddress.getByName(ipStr);
        } catch (UnknownHostException e) {
            return false;
        }
        for (CidrBlock block : blocks) {
            if (addr.getClass() == block.network().getClass() && matches(addr, block)) return true;
        }
        return false;
    }

    private boolean matches(InetAddress addr, CidrBlock block) {
        byte[] addrBytes    = addr.getAddress();
        byte[] networkBytes = block.network().getAddress();
        int    prefix       = block.prefix();

        int fullBytes = prefix / 8;
        int remainder = prefix % 8;

        for (int i = 0; i < fullBytes; i++) {
            if (addrBytes[i] != networkBytes[i]) return false;
        }
        if (remainder > 0) {
            int mask = 0xFF & (0xFF << (8 - remainder));
            if ((addrBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) return false;
        }
        return true;
    }

    private boolean isPrivateOrLoopback(String ipStr) {
        try {
            InetAddress addr = InetAddress.getByName(ipStr);
            // Covers RFC1918 private ranges (10.x, 172.16.x, 192.168.x)
            return addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static List<CidrBlock> buildBlocks() {
        List<CidrBlock> result = new java.util.ArrayList<>();
        for (String cidr : CF_CIDRS) {
            try {
                int slash   = cidr.indexOf('/');
                String host = cidr.substring(0, slash);
                int    prefix = Integer.parseInt(cidr.substring(slash + 1));
                InetAddress network = InetAddress.getByName(host);
                result.add(new CidrBlock(network, prefix));
            } catch (Exception e) {
                logger.warn("CIDR parse error '{}': {}", cidr, e.getMessage());
            }
        }
        return List.copyOf(result);
    }
}
