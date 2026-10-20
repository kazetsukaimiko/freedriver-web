package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.common.expression.Expression;
import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.ConfigValue;

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

    /**
     * Names already in the file, every HTTP permission key, and every OIDC
     * {@code tenant-enabled} name. A production launch ignores any other source for these.
     */
    static boolean locksBare(String bare, Set<String> owned) {
        if (owned.contains(bare) || bare.startsWith("quarkus.http.auth.permission.")) {
            return true;
        }
        return "quarkus.oidc.tenant-enabled".equals(bare)
                || (bare.startsWith("quarkus.oidc.") && bare.endsWith(".tenant-enabled"));
    }

    /**
     * Quarkus {@code @WithDefault} for a locked name that is not a line in
     * {@code auth-policy.properties}. This lock supplies the value.
     */
    ConfigValue builtInValue(String name) {
        String raw = builtInRaw(bareKey(name));
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
     * Known Quarkus defaults for locked names that are not lines in the file.
     * {@code shared=false} and {@code applies-to=ALL} for a permission already in the file
     * and for the {@code *} lookup. {@code tenant-enabled=true} only for the default tenant
     * and the literal {@code quarkus.oidc.*.tenant-enabled} lookup. A named tenant stays absent.
     */
    private String builtInRaw(String bare) {
        if ("quarkus.oidc.tenant-enabled".equals(bare) || "quarkus.oidc.*.tenant-enabled".equals(bare)) {
            return "true";
        }
        if (!bare.startsWith("quarkus.http.auth.permission.")) {
            return null;
        }
        String suffix;
        String value;
        if (bare.endsWith(".shared")) {
            suffix = ".shared";
            value = "false";
        } else if (bare.endsWith(".applies-to")) {
            suffix = ".applies-to";
            value = "ALL";
        } else {
            return null;
        }
        String key = bare.substring("quarkus.http.auth.permission.".length(), bare.length() - suffix.length());
        if ("*".equals(key) || permissionInFile(key)) {
            return value;
        }
        return null;
    }

    /** True when {@code auth-policy.properties} already has a line for this permission name. */
    private boolean permissionInFile(String key) {
        if (key.isEmpty() || key.indexOf('.') >= 0 || key.indexOf('*') >= 0) {
            return false;
        }
        String marker = "quarkus.http.auth.permission." + key + ".";
        for (String owned : bareKeys) {
            if (owned.startsWith(marker)) {
                return true;
            }
        }
        return false;
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
