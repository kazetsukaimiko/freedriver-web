package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import jakarta.annotation.Priority;

/**
 * In a production launch, auth-policy keys stay on {@code auth-policy.properties}
 * after every other interceptor. A locked name missing from that file keeps the
 * known Quarkus default this lock supplies, or stays absent.
 */
@Priority(AuthPolicyLockGuard.PRIORITY)
public final class AuthPolicyLockGuard implements ConfigSourceInterceptor {

    /** Nothing in the interceptor chain has a higher priority. */
    static final int PRIORITY = Integer.MAX_VALUE;

    private final AuthPolicyLock lock = AuthPolicyLock.load();

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (!lock.locks(name) || LaunchMode.current().isDevOrTest()) {
            return context.proceed(name);
        }
        ConfigValue resolved = context.proceed(name);
        String raw = lock.productionRaw(name);
        if (raw == null) {
            return lock.builtInValue(name);
        }
        if (lock.sameAsFile(resolved, raw)) {
            return resolved;
        }
        return lock.replacement(name, raw);
    }
}
