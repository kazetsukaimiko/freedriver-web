package io.freedriver.app.security;

import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilderCustomizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Appends {@link AuthPolicyLockGuard} after every other interceptor and drops a
 * different interceptor at the same priority.
 */
public final class AuthPolicyLockCustomizer implements SmallRyeConfigBuilderCustomizer {

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void configBuilder(SmallRyeConfigBuilder builder) {
        List<?> interceptors = builder.getInterceptors();
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            Object item = interceptors.get(i);
            if (priorityOf(item) == AuthPolicyLockGuard.PRIORITY && !isGuard(item)) {
                interceptors.remove(i);
            }
        }
        builder.withInterceptors(new AuthPolicyLockGuard());
    }

    private static int priorityOf(Object interceptorWithPriority) {
        try {
            Field priority = interceptorWithPriority.getClass().getDeclaredField("priority");
            priority.setAccessible(true);
            return priority.getInt(interceptorWithPriority);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read an interceptor priority", e);
        }
    }

    private static boolean isGuard(Object interceptorWithPriority) {
        try {
            Method getInterceptor = interceptorWithPriority.getClass()
                    .getDeclaredMethod("getInterceptor", ConfigSourceInterceptorContext.class);
            getInterceptor.setAccessible(true);
            Object interceptor = getInterceptor.invoke(interceptorWithPriority, new Object[] {null});
            return interceptor instanceof AuthPolicyLockGuard;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
