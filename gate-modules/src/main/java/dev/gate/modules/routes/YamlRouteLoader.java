package dev.gate.modules.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.Json;
import dev.gate.core.Logger;
import dev.gate.modules.cache.HttpCache;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registers the entries of a bundled {@code routes.yaml} as GET routes on a
 * {@link Gate} instance.
 *
 * <p>YAML format:
 * <pre>
 * routes:
 *   - path: /api/locations
 *     table: locations
 *     columns: [id, name, floor, location_code]
 *     cache: 30s          # required. 0s = no cache (hit DB per request); 10s/5m/1h = background refresh interval
 * </pre>
 *
 * Each entry generates {@code SELECT {columns} FROM {table}} and serves the
 * ResultSet as a JSON array. Table and column names are allowlist-validated
 * (alphanumeric + underscore) to prevent SQL injection. Routes needing WHERE /
 * JOIN / ORDER BY should be written as Java controllers instead.
 *
 * <p>Routes with a positive {@code cache} value are served through
 * {@link HttpCache} (ETag / 304 / gzip / Vary) and rebuilt on a background
 * schedule; call {@link #refreshAll()} once at startup to warm them and
 * {@link #startBackgroundRefreshes(ScheduledExecutorService)} to keep them
 * fresh.</p>
 */
public class YamlRouteLoader {

    private static final Logger log = new Logger(YamlRouteLoader.class);
    private static final ObjectMapper MAPPER = Json.MAPPER;
    // Only alphanumerics and underscores are allowed in table/column names (SQL-injection guard)
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    // cache field format: <number><unit s|m|h>
    private static final Pattern CACHE_PATTERN = Pattern.compile("^(\\d+)\\s*([smh])$");

    private record YamlRoute(String path, String sql, long cacheSeconds,
                            AtomicReference<HttpCache.Entry> cache) {}

    // Holds only routes with cacheSeconds > 0; fixed once by load().
    private static volatile List<YamlRoute> CACHED_ROUTES = List.of();

    @SuppressWarnings("unchecked")
    public static void load(Gate gate) {
        try (InputStream is = YamlRouteLoader.class.getResourceAsStream("/routes.yaml")) {
            if (is == null) {
                log.info("routes.yaml not found — YAML routes skipped");
                return;
            }

            // The bundled routes.yaml is trusted, but SafeConstructor still avoids
            // arbitrary object instantiation.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> config = yaml.load(is);
            if (config == null) return;

            List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
            if (routes == null || routes.isEmpty()) {
                log.info("routes.yaml: no routes defined");
                return;
            }

            List<YamlRoute> cached = new ArrayList<>();
            int registered = 0;
            for (Map<String, Object> entry : routes) {
                String path          = (String)       entry.get("path");
                String table         = (String)       entry.get("table");
                List<String> columns = (List<String>) entry.get("columns");

                if (path == null || table == null || columns == null || columns.isEmpty()) {
                    log.warn("YAML route skipped — missing path/table/columns: {}", entry);
                    continue;
                }
                if (!SAFE_IDENT.matcher(table).matches()) {
                    log.warn("YAML route '{}' skipped — unsafe table name: {}", path, table);
                    continue;
                }
                boolean safe = true;
                for (String col : columns) {
                    if (!SAFE_IDENT.matcher(col).matches()) {
                        log.warn("YAML route '{}' skipped — unsafe column name: {}", path, col);
                        safe = false;
                        break;
                    }
                }
                if (!safe) continue;

                // 'cache' is mandatory. A missing value is treated as a config
                // mistake and skipped (write an explicit 'cache: 0s' to disable caching).
                if (!entry.containsKey("cache")) {
                    log.warn("YAML route '{}' skipped — 'cache' is required (e.g. 0s/30s/5m/1h)", path);
                    continue;
                }

                String sql = "SELECT " + String.join(", ", columns) + " FROM " + table;
                long cacheSeconds = parseCacheSeconds(entry.get("cache"));

                if (cacheSeconds > 0) {
                    YamlRoute route = new YamlRoute(path, sql, cacheSeconds, new AtomicReference<>());
                    cached.add(route);
                    registerCachedRoute(gate, route);
                    log.info("YAML route: GET {} → {} (cache {}s)", path, sql, cacheSeconds);
                } else {
                    registerDirectRoute(gate, path, table, sql);
                    log.info("YAML route: GET {} → {} (no cache)", path, sql);
                }
                registered++;
            }
            CACHED_ROUTES = List.copyOf(cached);
            log.info("YAML routes: {} registered ({} cached)", registered, CACHED_ROUTES.size());

        } catch (Exception e) {
            log.warn("Failed to load routes.yaml: {}", e.getMessage(), e);
        }
    }

    /** Rebuilds every cached route — call at startup to warm caches, or after admin edits. */
    public static void refreshAll() throws Exception {
        Exception first = null;
        for (YamlRoute r : CACHED_ROUTES) {
            try {
                refreshOne(r);
            } catch (Exception e) {
                log.warn("YAML cache refresh failed {}: {}", r.path(), e.getMessage());
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    /** Schedules a background refresh per cached route at its own TTL interval. */
    public static void startBackgroundRefreshes(ScheduledExecutorService bg) {
        for (YamlRoute r : CACHED_ROUTES) {
            bg.scheduleAtFixedRate(() -> {
                try {
                    refreshOne(r);
                } catch (Exception e) {
                    log.warn("YAML cache poll failed {}: {}", r.path(), e.getMessage());
                }
            }, r.cacheSeconds(), r.cacheSeconds(), TimeUnit.SECONDS);
        }
        log.info("YAML cache background refreshes scheduled: {} routes", CACHED_ROUTES.size());
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Converts the cache field to a refresh interval in seconds. 0 = disabled.
     * Null/blank/invalid values log a warning and disable caching.
     * Package-private for tests.
     */
    static long parseCacheSeconds(Object raw) {
        if (raw == null) return 0;
        Matcher m = CACHE_PATTERN.matcher(raw.toString().trim().toLowerCase());
        if (!m.matches()) {
            log.warn("invalid cache value '{}' → cache disabled", raw);
            return 0;
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> n;
            case "m" -> n * 60;
            case "h" -> n * 3600;
            default  -> 0;
        };
    }

    // Cached serving handler (shared behaviour via HttpCache.serveJson)
    private static void registerCachedRoute(Gate gate, YamlRoute route) {
        // s-maxage (edge) can exceed the refresh interval when writes purge the CDN
        // explicitly; max-age (browser) cannot be purged, so it stays at the interval.
        String cacheControl = "public, max-age=" + route.cacheSeconds()
                + ", s-maxage=300, stale-while-revalidate=600";
        gate.get(route.path(), ctx -> {
            HttpCache.Entry entry = route.cache().get();
            if (entry == null) {
                ctx.status(503).json(Map.of("error", "warming up"));
                return;
            }
            HttpCache.serveJson(ctx, entry, cacheControl);
        });
    }

    // Direct handler hitting the DB per request (cache: 0s routes)
    private static void registerDirectRoute(Gate gate, String path, String table, String sql) {
        gate.get(path, ctx -> {
            try (Connection conn = Database.getConnection();
                 Statement  stmt = conn.createStatement();
                 ResultSet  rs   = stmt.executeQuery(sql)) {
                ctx.jsonBytes(MAPPER.writeValueAsBytes(resultSetToArray(rs)));
            } catch (java.sql.SQLSyntaxErrorException e) {
                log.warn("YAML route table not found GET {}: {}", path, e.getMessage());
                ctx.status(503).json(Map.of("error", "table not found: " + table));
            } catch (Exception e) {
                log.error("YAML route error GET {}: {}", path, e.getMessage(), e);
                ctx.status(503).json(Map.of("error", "database error"));
            }
        });
    }

    // Rebuilds a single cached route from the DB
    private static void refreshOne(YamlRoute route) throws Exception {
        try (Connection conn = Database.getConnection();
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(route.sql())) {
            byte[] json = MAPPER.writeValueAsBytes(resultSetToArray(rs));
            route.cache().set(HttpCache.entryOf(json));
        }
    }

    private static ArrayNode resultSetToArray(ResultSet rs) throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rs.next()) {
            ObjectNode node = arr.addObject();
            for (int i = 1; i <= colCount; i++) {
                putColumn(node, meta.getColumnLabel(i), meta.getColumnType(i), rs, i);
            }
        }
        return arr;
    }

    private static void putColumn(ObjectNode node, String name, int sqlType, ResultSet rs, int idx)
            throws Exception {
        switch (sqlType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
                int v = rs.getInt(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.BIGINT -> {
                long v = rs.getLong(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.FLOAT, Types.REAL -> {
                float v = rs.getFloat(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.DOUBLE -> {
                double v = rs.getDouble(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            case Types.BOOLEAN, Types.BIT -> {
                boolean v = rs.getBoolean(idx);
                if (rs.wasNull()) node.putNull(name); else node.put(name, v);
            }
            default -> {
                String v = rs.getString(idx);
                if (v == null) node.putNull(name); else node.put(name, v);
            }
        }
    }
}
