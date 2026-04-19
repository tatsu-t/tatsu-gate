package dev.gate.core;

import dev.gate.annotation.AnnotationScanner;
import dev.gate.annotation.GateController;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Gate {

    private final Router router = new Router();
    private final AnnotationScanner scanner = new AnnotationScanner(router);
    private String corsOrigin = null;
    private final Logger logger = new Logger(Gate.class);

    private ErrorHandler errorHandler = (ctx, e) -> {
        logger.error("Unhandled error: " + e.getMessage(), e);
        ctx.status(500);
        ctx.result("500 Internal Server Error");
    };

    public void register(Object controller) {
        scanner.scan(controller);
    }

    public Gate cors(String allowedOrigin) {
        if ("*".equals(allowedOrigin)) {
            logger.warn("CORS configured with wildcard '*' — credentials cannot be used with this origin");
        }
        this.corsOrigin = allowedOrigin;
        return this;
    }

    public Gate errorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
        return this;
    }

    private int wsMaxMessageSize = 64 * 1024;

    public Gate wsMaxMessageSize(int bytes) {
        this.wsMaxMessageSize = bytes;
        return this;
    }

    public void start(int port) throws Exception {
        Server server = new Server(port);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        final int finalWsMaxSize = this.wsMaxMessageSize;
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setMaxTextMessageSize(finalWsMaxSize);
            router.getWsRoutes().forEach((path, wsHandler) -> {
                wsContainer.addMapping(path, (req, res) -> new WsAdapter(wsHandler));
            });
        });

        final String finalCorsOrigin = this.corsOrigin;
        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                String path = request.getPathInfo();
                if (path == null || path.isEmpty()) path = request.getServletPath();
                if (path == null || path.isEmpty()) path = "/";
                if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

                Context ctx = new Context(path, request);

                try {
                    if (finalCorsOrigin != null) {
                        response.setHeader("Access-Control-Allow-Origin", finalCorsOrigin);
                        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
                    }

                    if ("OPTIONS".equals(request.getMethod())) {
                        response.setStatus(204);
                        return;
                    }

                    String key = request.getMethod() + ":" + path;

                    router.find(key).ifPresentOrElse(
                        handler -> handler.handle(ctx),
                        () -> {
                            ctx.status(404);
                            ctx.result("404 Not Found");
                        }
                    );
                } catch (Exception e) {
                    errorHandler.handle(ctx, e);
                }

                // ヘッダー・ContentType は Writer 取得前に設定する
                response.setStatus(ctx.statusCode());
                ctx.headers().forEach(response::setHeader);
                response.setContentType(ctx.contentType());

                PrintWriter writer = response.getWriter();
                writer.print(ctx.responseBody());
                writer.flush();
            }
        }), "/*");

        server.setHandler(context);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down server...");
            try { server.stop(); } catch (Exception ex) { logger.error("Error stopping server", ex); }
        }));

        logger.info("Starting on port " + port);
        try {
            server.start();
            server.join();
        } catch (Exception e) {
            server.stop();
            throw e;
        }
    }

    public void scan(String packageName) throws Exception {
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        Enumeration<URL> resources = classLoader.getResources(packagePath);
        if (!resources.hasMoreElements()) {
            logger.warn("Package not found: " + packageName);
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            switch (url.getProtocol()) {
                case "file" -> scanDirectory(packageName, classLoader, Paths.get(url.toURI()).toFile());
                case "jar"  -> scanJar(packageName, classLoader, url);
                default     -> logger.warn("Unsupported protocol: " + url.getProtocol());
            }
        }
    }

    private void scanDirectory(String packageName, ClassLoader classLoader, File directory) throws Exception {
        if (!directory.isDirectory()) return;

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(packageName + "." + file.getName(), classLoader, file);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                loadAndRegister(className, classLoader);
            }
        }
    }

    private void scanJar(String packageName, ClassLoader classLoader, URL url) throws Exception {
        JarURLConnection conn = (JarURLConnection) url.openConnection();
        conn.setUseCaches(false);
        String packagePath = packageName.replace('.', '/') + "/";

        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith(packagePath) && entry.getName().endsWith(".class")) {
                    String className = entry.getName().replace('/', '.').replace(".class", "");
                    loadAndRegister(className, classLoader);
                }
            }
        }
    }

    private void loadAndRegister(String className, ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            if (clazz.isAnnotationPresent(GateController.class)) {
                register(clazz.getDeclaredConstructor().newInstance());
                logger.info("Loaded controller: " + className);
            }
        } catch (Exception e) {
            logger.error("Failed to load class: " + className + " - " + e.getMessage(), e);
        }
    }
}
