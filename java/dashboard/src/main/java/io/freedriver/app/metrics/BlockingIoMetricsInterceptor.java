package io.freedriver.app.metrics;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits {@code io.blocking} on CDI invocations of {@link BlockingIo} beans.
 * Grafana sink types are skipped even if they also carry the binding.
 */
@BlockingIo
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 50)
public class BlockingIoMetricsInterceptor {
    static final String KEY = "io.blocking";

    private final GrafanaMetricsEmitter emitter;

    @Inject
    public BlockingIoMetricsInterceptor(GrafanaMetricsEmitter emitter) {
        this.emitter = emitter;
    }

    @AroundInvoke
    public Object around(InvocationContext ctx) throws Exception {
        if (skip(ctx)) {
            return ctx.proceed();
        }
        String type = typeName(ctx);
        String method = ctx.getMethod() == null ? "unknown" : ctx.getMethod().getName();
        long start = System.nanoTime();
        try {
            Object result = ctx.proceed();
            emit(type, method, "ok", System.nanoTime() - start);
            return result;
        } catch (Exception e) {
            emit(type, method, "error", System.nanoTime() - start);
            throw e;
        }
    }

    static boolean skip(InvocationContext ctx) {
        if (ctx == null) {
            return true;
        }
        if (hasSink(ctx.getTarget() == null ? null : ctx.getTarget().getClass())) {
            return true;
        }
        return ctx.getMethod() != null && hasSink(ctx.getMethod().getDeclaringClass());
    }

    static boolean hasSink(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.isAnnotationPresent(GrafanaMetricsSink.class)) {
                return true;
            }
        }
        return false;
    }

    private void emit(String type, String method, String outcome, long nanos) {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("type", type);
        tags.put("method", method);
        tags.put("outcome", outcome);
        emitter.increment(KEY, tags);
        Map<String, Object> duration = new LinkedHashMap<>(tags);
        duration.put("value", nanos);
        emitter.count(KEY + ".ns", duration);
    }

    private static String typeName(InvocationContext ctx) {
        Class<?> type = ctx.getTarget() == null
                ? (ctx.getMethod() == null ? null : ctx.getMethod().getDeclaringClass())
                : ctx.getTarget().getClass();
        if (type == null) {
            return "unknown";
        }
        String name = type.getSimpleName();
        int proxy = name.indexOf('_');
        if (proxy > 0 && name.contains("ClientProxy")) {
            return name.substring(0, proxy);
        }
        if (name.contains("$$")) {
            return type.getSuperclass() == null ? name : type.getSuperclass().getSimpleName();
        }
        return name;
    }
}
