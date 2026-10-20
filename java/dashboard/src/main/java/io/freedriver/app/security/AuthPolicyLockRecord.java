package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

/**
 * Remembers the profile-selected auth-policy value before an application interceptor runs.
 */
@Priority(AuthPolicyLockRecord.PRIORITY)
public final class AuthPolicyLockRecord implements ConfigSourceInterceptor {

    /** Just under {@link Priorities#APPLICATION}, outside profile and expression handling. */
    static final int PRIORITY = Priorities.APPLICATION - 1;

    private final AuthPolicyLock lock = AuthPolicyLock.load();

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue resolved = context.proceed(name);
        if (lock.locks(name) && !LaunchMode.current().isDevOrTest()) {
            AuthPolicyLock.record(name, resolved);
        }
        return resolved;
    }
}
