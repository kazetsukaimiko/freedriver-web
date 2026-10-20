package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.common.expression.Expression;
import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.DefaultValuesConfigSource;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static io.smallrye.common.expression.Expression.Flag.DOUBLE_COLON;
import static io.smallrye.common.expression.Expression.Flag.LENIENT_SYNTAX;
import static io.smallrye.common.expression.Expression.Flag.NO_SMART_BRACES;
import static io.smallrye.common.expression.Expression.Flag.NO_TRIM;

/** Auth-policy keys and the prod-profile value from {@code auth-policy.properties}. */
final class AuthPolicyLock {

    private final Map<String, String> properties;
    private final Set<String> bareKeys;

    private AuthPolicyLock(Map<String, String> properties, Set<String> bareKeys) {
        this.properties = properties;
        this.bareKeys = bareKeys;
    }

    static AuthPolicyLock load() {
        Map<String, String> properties = AuthPolicyConfigSource.load(
                Thread.currentThread().getContextClassLoader());
        return new AuthPolicyLock(properties, bareKeys(properties.keySet()));
    }

    boolean locks(String name) {
        return locksBare(bareKey(name), bareKeys);
    }

    /** Quarkus {@code @WithDefault} values. A prod lock must not drop these when the file omits the name. */
    static boolean isFrameworkDefault(ConfigValue value) {
        return value != null && DefaultValuesConfigSource.NAME.equals(value.getConfigSourceName());
    }

    /**
     * Names already in the file, every HTTP permission key, and the OIDC switches that
     * turn a tenant off. A production launch ignores any other source for these names.
     */
    static boolean locksBare(String bare, Set<String> owned) {
        if (owned.contains(bare) || bare.startsWith("quarkus.http.auth.permission.")) {
            return true;
        }
        return "quarkus.oidc.tenant-enabled".equals(bare)
                || (bare.startsWith("quarkus.oidc.") && bare.endsWith(".tenant-enabled"));
    }

    /** Value for the exact name the profile interceptor asks for. */
    ConfigValue fileValue(String name) {
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
     * Prod-profile entry for {@code name}. A production launch uses this when a later
     * interceptor replaces the chain's value.
     */
    String productionRaw(String name) {
        String bare = bareKey(name);
        String profiled = properties.get("%" + LaunchMode.PROD_PROFILE + "." + bare);
        if (profiled != null) {
            return profiled;
        }
        return properties.get(bare);
    }

    ConfigValue replacement(String name, String raw) {
        Expansion expansion = expand(raw);
        ConfigValue.ConfigValueBuilder builder = ConfigValue.builder()
                .withName(name)
                .withValue(expansion.value)
                .withRawValue(raw)
                .withProfile(LaunchMode.PROD_PROFILE)
                .withConfigSourceName(AuthPolicyConfigSource.RESOURCE)
                .withConfigSourceOrdinal(AuthPolicyConfigSource.ORDINAL);
        if (expansion.unresolved) {
            builder.addProblem(unresolved(raw, name));
        }
        return builder.build();
    }

    /** True when {@code resolved} is still the file entry, after expression expansion. */
    boolean sameAsFile(ConfigValue resolved, String raw) {
        if (resolved == null || resolved.getValue() == null || !raw.equals(resolved.getRawValue())) {
            return false;
        }
        if (!AuthPolicyConfigSource.RESOURCE.equals(resolved.getConfigSourceName())) {
            return false;
        }
        Expansion expansion = expand(raw);
        return expansion.value.equals(resolved.getValue()) && expansion.unresolved == resolved.hasProblems();
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

    private static Set<String> bareKeys(Iterable<String> names) {
        Set<String> bare = new HashSet<>();
        for (String name : names) {
            bare.add(bareKey(name));
        }
        return Set.copyOf(bare);
    }

    /**
     * System property, then environment variable. Matches SmallRye's ordinals for those
     * two sources. A missing name with no default stays unresolved so prod startup fails closed.
     */
    private static Expansion expand(String raw) {
        if (raw.indexOf('$') < 0) {
            return new Expansion(raw, false);
        }
        boolean[] unresolved = {false};
        String value = Expression.compile(escapeDollar(raw), LENIENT_SYNTAX, NO_TRIM, NO_SMART_BRACES, DOUBLE_COLON)
                .evaluate((context, builder) -> {
                    String key = context.getKey();
                    String found = System.getProperty(key);
                    if (found == null) {
                        found = System.getenv(key);
                    }
                    if (found != null) {
                        builder.append(found);
                        return;
                    }
                    if (context.hasDefault()) {
                        context.expandDefault();
                        return;
                    }
                    unresolved[0] = true;
                });
        return new Expansion(value, unresolved[0]);
    }

    /** SmallRye turns {@code \$} into the expression escape before compiling. */
    private static String escapeDollar(String value) {
        int index = value.indexOf("\\$");
        if (index < 0) {
            return value;
        }
        int start = 0;
        StringBuilder builder = new StringBuilder();
        while (index >= 0) {
            builder.append(value, start, index).append("$$");
            start = index + 2;
            index = value.indexOf("\\$", start);
        }
        builder.append(value.substring(start));
        return builder.toString();
    }

    private static ConfigValidationException.Problem unresolved(String raw, String name) {
        NoSuchElementException missing = new NoSuchElementException("Could not expand " + raw + " for " + name);
        try {
            Constructor<ConfigValidationException.Problem> ctor = ConfigValidationException.Problem.class
                    .getDeclaredConstructor(RuntimeException.class);
            ctor.setAccessible(true);
            return ctor.newInstance(missing);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(missing.getMessage(), missing);
        }
    }

    private record Expansion(String value, boolean unresolved) {
    }
}
