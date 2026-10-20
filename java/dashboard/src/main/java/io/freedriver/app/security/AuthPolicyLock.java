package io.freedriver.app.security;

import io.smallrye.config.ConfigValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Keys and the profile-selected value recorded before application interceptors run. */
final class AuthPolicyLock {

    private static final ThreadLocal<Map<String, ConfigValue>> RECORDED =
            ThreadLocal.withInitial(HashMap::new);

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
        return bareKeys.contains(bareKey(name));
    }

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

    static void record(String name, ConfigValue value) {
        RECORDED.get().put(name, value);
    }

    static boolean has(String name) {
        return RECORDED.get().containsKey(name);
    }

    static ConfigValue take(String name) {
        return RECORDED.get().remove(name);
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
}
