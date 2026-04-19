package dev.gate.annotation;

import dev.gate.core.Logger;
import dev.gate.core.Router;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import dev.gate.mapping.WsMapping;

public class AnnotationScanner {

    private static final Logger logger = new Logger(AnnotationScanner.class);
    private final Router router;

    public AnnotationScanner(Router router) {
        this.router = router;
    }

    public void scan(Object controller) {
        Class<?> clazz = controller.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            if (method.isAnnotationPresent(GetMapping.class)) {
                String path = method.getAnnotation(GetMapping.class).value();
                router.register("GET:" + path, ctx -> invoke(method, controller, ctx));
            }

            if (method.isAnnotationPresent(PostMapping.class)) {
                String path = method.getAnnotation(PostMapping.class).value();
                router.register("POST:" + path, ctx -> invoke(method, controller, ctx));
            }
            if (method.isAnnotationPresent(WsMapping.class)) {
                String path = method.getAnnotation(WsMapping.class).value();
                router.registerWs(path, (ctx, message) -> {
                    try {
                        method.invoke(controller, ctx, message);
                    } catch (Exception e) {
                        logger.error("WS handler error in " + method.getName() + ": " + e.getMessage(), e);
                    }
                });
            }
        }
    }

    private void invoke(Method method, Object controller, Context ctx) {
        try {
            method.invoke(controller, ctx);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access handler: " + method.getName(), e);
        }
    }
}
