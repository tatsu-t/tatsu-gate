package dev.gate.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Context {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger logger = new Logger(Context.class);

    private final String path;
    private final HttpServletRequest request;
    private String responseBody = "";
    private String contentType = "text/plain; charset=utf-8";
    private int statusCode = 200;
    private final Map<String, String> headers = new HashMap<>();
    private Map<String, String> pathParams = Map.of();
    private String cachedBody = null;

    public Context(String path, HttpServletRequest request) {
        this.path = path;
        this.request = request;
    }

    public String path() { return path; }

    public String pathParam(String name) { return pathParams.get(name); }

    void setPathParams(Map<String, String> params) { this.pathParams = params; }

    public String query(String key) { return request.getParameter(key); }

    public String body() {
        if (cachedBody != null) return cachedBody;
        try {
            cachedBody = request.getReader().lines().collect(Collectors.joining());
            return cachedBody;
        } catch (IOException e) {
            logger.error("Failed to read request body: " + e.getMessage(), e);
            return "";
        }
    }

    public <T> T bodyAs(Class<T> type) {
        String raw = body();
        if (raw == null || raw.isEmpty()) return null;
        try {
            return mapper.readValue(raw, type);
        } catch (Exception e) {
            logger.warn("Failed to parse request body as " + type.getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    public void status(int code) { this.statusCode = code; }
    public int statusCode() { return statusCode; }

    public void result(String body) { this.responseBody = body; }

    public void json(Object object) {
        try {
            this.responseBody = mapper.writeValueAsString(object);
            this.contentType = "application/json; charset=utf-8";
        } catch (Exception e) {
            logger.error("Failed to serialize response: " + e.getMessage(), e);
            this.responseBody = "{}";
        }
    }

    public void header(String key, String value) {
        if (key == null || key.contains("\r") || key.contains("\n") ||
            value == null || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("Header contains illegal characters");
        }
        headers.put(key, value);
    }

    public String responseBody() { return responseBody; }
    public String contentType() { return contentType; }
    public Map<String, String> headers() { return Collections.unmodifiableMap(headers); }
}
