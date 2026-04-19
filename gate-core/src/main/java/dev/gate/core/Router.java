package dev.gate.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Router {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Router.class);

    public record RouteMatch(Handler handler, Map<String, String> pathParams) {}

    private record PatternRoute(String method, String[] segments, String[] paramNames, Handler handler) {}

    private final Map<String, Handler> exactRoutes = new ConcurrentHashMap<>();
    private final List<PatternRoute> patternRoutes = new CopyOnWriteArrayList<>();
    private final Map<String, WsHandle> wsRoutes = new ConcurrentHashMap<>();

    private static String normalizePath(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public void register(String key, Handler handler) {
        int colonIdx = key.indexOf(':');
        String method = key.substring(0, colonIdx);
        String rawPath = key.substring(colonIdx + 1);
        String path = normalizePath(rawPath);
        String normalizedKey = method + ":" + path;

        if (path.contains("{")) {
            String[] segments = path.split("/", -1);
            String[] paramNames = new String[segments.length];
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].startsWith("{") && segments[i].endsWith("}")) {
                    String name = segments[i].substring(1, segments[i].length() - 1);
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException("Empty path parameter name in: " + path);
                    }
                    if (!seen.add(name)) {
                        throw new IllegalArgumentException("Duplicate path parameter '{" + name + "}' in: " + path);
                    }
                    paramNames[i] = name;
                    segments[i] = null; // null = param slot
                }
            }

            // ルート競合検出
            for (PatternRoute existing : patternRoutes) {
                if (existing.method().equals(method) && existing.segments().length == segments.length) {
                    boolean ambiguous = true;
                    for (int i = 0; i < segments.length; i++) {
                        boolean curIsParam = segments[i] == null;
                        boolean exIsParam = existing.segments()[i] == null;
                        if (!curIsParam && !exIsParam && !segments[i].equals(existing.segments()[i])) {
                            ambiguous = false;
                            break;
                        }
                    }
                    if (ambiguous) {
                        log.warn("Ambiguous route: {} {} may conflict with an existing pattern route", method, path);
                    }
                }
            }

            patternRoutes.add(new PatternRoute(method, segments, paramNames, handler));
        } else {
            exactRoutes.put(normalizedKey, handler);
        }
    }

    public Optional<RouteMatch> find(String key) {
        // 完全一致を優先
        Handler exact = exactRoutes.get(key);
        if (exact != null) {
            return Optional.of(new RouteMatch(exact, Map.of()));
        }

        // パターンマッチ
        int colonIdx = key.indexOf(':');
        String method = key.substring(0, colonIdx);
        String path = key.substring(colonIdx + 1);
        String[] requestSegments = path.split("/", -1);

        for (PatternRoute route : patternRoutes) {
            if (!route.method().equals(method)) continue;
            if (route.segments().length != requestSegments.length) continue;

            Map<String, String> params = new HashMap<>();
            boolean match = true;
            for (int i = 0; i < route.segments().length; i++) {
                if (route.segments()[i] == null) {
                    // 空セグメントを拒否
                    if (requestSegments[i].isEmpty()) {
                        match = false;
                        break;
                    }
                    params.put(route.paramNames()[i], requestSegments[i]);
                } else if (!route.segments()[i].equals(requestSegments[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return Optional.of(new RouteMatch(route.handler(), Collections.unmodifiableMap(params)));
            }
        }

        return Optional.empty();
    }

    public void registerWs(String path, WsHandle handler) {
        wsRoutes.put(path, handler);
    }

    public Optional<WsHandle> findWs(String path) {
        return Optional.ofNullable(wsRoutes.get(path));
    }

    public Map<String, WsHandle> getWsRoutes() {
        return Collections.unmodifiableMap(wsRoutes);
    }
}
