package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

/**
 * In production, auth-policy keys come from {@code auth-policy.properties}.
 * Test and dev launch modes pass the value through so a Quarkus test can boot.
 */
@Priority(AuthPolicyLockInterceptor.PRIORITY)
public final class AuthPolicyLockInterceptor implements ConfigSourceInterceptor {

    /** Inside the profile interceptor ({@code LIBRARY + 200}) and outside the sources. */
    static final int PRIORITY = Priorities.LIBRARY + 150;

    private final AuthPolicyLock lock = AuthPolicyLock.load();

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (!lock.locks(name) || LaunchMode.current().isDevOrTest()) {
            return context.proceed(name);
        }
        return lock.fileValue(name);
    }
}
