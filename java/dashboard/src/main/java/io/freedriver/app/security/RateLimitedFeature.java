package io.freedriver.app.security;

import io.freedriver.dashboard.api.RateLimited;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;

@Provider
public class RateLimitedFeature implements DynamicFeature {

    @Inject
    RateLimitedFilter filter;

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        if (binding(resourceInfo) == null) {
            return;
        }
        context.register(filter, Priorities.AUTHORIZATION + 100);
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
