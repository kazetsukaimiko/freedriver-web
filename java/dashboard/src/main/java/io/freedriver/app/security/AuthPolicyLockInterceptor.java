package io.freedriver.app.security;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Auth-policy keys come from {@code auth-policy.properties}.
 * A Quarkus test-profile or startup-override source supplies its own value for those keys.
 */
@Priority(AuthPolicyLockInterceptor.PRIORITY)
public final class AuthPolicyLockInterceptor implements ConfigSourceInterceptor {

    /**
     * Inside {@code ProfileConfigSourceInterceptor} ({@code LIBRARY + 200}) so that interceptor
     * still chooses the active profile, and outside the config sources.
     */
    static final int PRIORITY = Priorities.LIBRARY + 150;

    private final Map<String, String> properties;
    private final Set<String> bareKeys;

    public AuthPolicyLockInterceptor() {
        this.properties = AuthPolicyConfigSource.load(Thread.currentThread().getContextClassLoader());
        this.bareKeys = bareKeys(properties.keySet());
    }

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (!bareKeys.contains(bareKey(name))) {
            return context.proceed(name);
        }
        ConfigValue resolved = context.proceed(name);
        if (resolved != null && quarkusTestOverride(resolved)) {
            return resolved;
        }
        String raw = properties.get(name);
        if (raw == null) {
            return null;
        }
        return ConfigValue.builder()
                .withName(name)
                .withValue(raw)
                .withRawValue(raw)
                .withConfigSourceName(AuthPolicyConfigSource.RESOURCE)
                .withConfigSourceOrdinal(AuthPolicyConfigSource.ORDINAL)
                .build();
    }

    /**
     * Quarkus writes test-profile and test-resource overrides into a temporary
     * {@code application.properties} whose path contains one of these markers.
     */
    static boolean quarkusTestOverride(ConfigValue value) {
        String source = value.getConfigSourceName();
        return source != null && (source.contains("test-profile") || source.contains("startup-override"));
    }

    static Set<String> bareKeys(Iterable<String> names) {
        Set<String> bare = new HashSet<>();
        for (String name : names) {
            bare.add(bareKey(name));
        }
        return Set.copyOf(bare);
    }

    static String bareKey(String name) {
        if (!name.isEmpty() && name.charAt(0) == '%') {
            int dot = name.indexOf('.');
            if (dot > 1 && dot < name.length() - 1) {
                return name.substring(dot + 1);
            }
        }
        return name;
    }
}
