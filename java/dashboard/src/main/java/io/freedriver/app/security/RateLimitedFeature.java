package io.freedriver.app.security;

import io.freedriver.dashboard.api.RateLimited;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads {@link RateLimited#value()} once per route and registers the filter for that route's
 * bucket. A value outside {@link #BUCKETS} stops start-up.
 */
@Provider
public class RateLimitedFeature implements DynamicFeature {

    static final int PRIORITY = Priorities.AUTHORIZATION + 100;

    static final Set<String> BUCKETS = Set.of("appliances.commands", "instances", "invites", "phones");

    private final Map<String, RateLimitedFilter> filters = new ConcurrentHashMap<>();

    @Inject
    CallerRateLimiter limiter;

    @Inject
    SecurityIdentity identity;

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        RateLimited binding = binding(resourceInfo);
        if (binding == null) {
            return;
        }
        String bucket = binding.value();
        if (bucket == null || !BUCKETS.contains(bucket)) {
            throw new IllegalStateException("@RateLimited(\"" + bucket + "\") on " + route(resourceInfo)
                    + " names no bucket; allowed: " + BUCKETS);
        }
        context.register(filters.computeIfAbsent(bucket, b -> new RateLimitedFilter(b, limiter, identity)), PRIORITY);
    }

    private static String route(ResourceInfo resourceInfo) {
        Class<?> clazz = resourceInfo.getResourceClass();
        Method method = resourceInfo.getResourceMethod();
        return (clazz == null ? "?" : clazz.getName()) + "." + (method == null ? "?" : method.getName());
    }

    private static RateLimited binding(ResourceInfo resourceInfo) {
        Method method = resourceInfo.getResourceMethod();
        if (method != null) {
            RateLimited direct = method.getAnnotation(RateLimited.class);
            if (direct != null) {
                return direct;
            }
            Class<?> clazz = resourceInfo.getResourceClass();
            if (clazz != null) {
                for (Class<?> iface : clazz.getInterfaces()) {
                    try {
                        RateLimited inherited = iface
                                .getMethod(method.getName(), method.getParameterTypes())
                                .getAnnotation(RateLimited.class);
                        if (inherited != null) {
                            return inherited;
                        }
                    } catch (NoSuchMethodException ignored) {
                        // This interface does not declare the resource method.
                    }
                }
            }
        }
        if (resourceInfo.getResourceClass() != null) {
            return resourceInfo.getResourceClass().getAnnotation(RateLimited.class);
        }
        return null;
    }
}
