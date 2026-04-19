package dev.gate.core;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class Router {

    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final Map<String, WsHandle> wsRoutes = new ConcurrentHashMap<>();

    public void register(String key, Handler handler) {
        routes.put(key, handler);
    }

    public Optional<Handler> find(String key) {
        return Optional.ofNullable(routes.get(key));
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
