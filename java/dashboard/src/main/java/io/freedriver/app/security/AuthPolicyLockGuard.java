package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

/**
 * In production, puts back the auth-policy value after an interceptor at
 * {@link Priorities#APPLICATION}.
 */
@Priority(AuthPolicyLockGuard.PRIORITY)
public final class AuthPolicyLockGuard implements ConfigSourceInterceptor {

    /** Outside {@link Priorities#APPLICATION}. */
    static final int PRIORITY = Priorities.APPLICATION + 1;

    private final AuthPolicyLock lock = AuthPolicyLock.load();

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue resolved = context.proceed(name);
        try {
            if (!lock.locks(name) || LaunchMode.current().isDevOrTest() || !AuthPolicyLock.has(name)) {
                return resolved;
            }
            return AuthPolicyLock.take(name);
        } finally {
            AuthPolicyLock.take(name);
        }
    }
}
