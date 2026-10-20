package io.freedriver.app.security;

import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

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
        if (resourceInfo.getResourceMethod() != null) {
            RateLimited method = resourceInfo.getResourceMethod().getAnnotation(RateLimited.class);
            if (method != null) {
                return method;
            }
        }
        if (resourceInfo.getResourceClass() != null) {
            return resourceInfo.getResourceClass().getAnnotation(RateLimited.class);
        }
        return null;
    }
}
