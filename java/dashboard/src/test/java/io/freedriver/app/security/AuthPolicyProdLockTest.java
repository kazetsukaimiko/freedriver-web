package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.annotation.Priority;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prod profile keeps OIDC on, the HTTP policies authenticated, and the command
 * rate limit at 30 per 60s when another source raises its ordinal.
 */
class AuthPolicyProdLockTest {

    @Test
    void prod_keeps_oidc_on_authenticated_policies_and_the_rate_limit() {
        LaunchMode previous = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            prodKeepsThePolicy();
        } finally {
            LaunchMode.set(previous);
        }
    }

    private void prodKeepsThePolicy() {
        ConfigSource propertiesFile = propertiesFileAboveThePolicy();
        ConfigSource jar = jarAboveThePolicy();
        SmallRyeConfig open = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withProfile("prod")
                .withSources(new AuthPolicyConfigSource(), propertiesFile, jar)
                .build();
        assertEquals(
                "false",
                open.getConfigValue("quarkus.oidc.enabled").getValue(),
                "the fixture source has to outrank auth-policy.properties before the lock");

        SmallRyeConfig prod = prodConfig(propertiesFile, jar);
        assertProdPolicy(prod);
        assertEquals(
                AuthPolicyConfigSource.RESOURCE,
                prod.getConfigValue("quarkus.oidc.enabled").getConfigSourceName());
    }

    @Test
    void prod_ignores_a_source_named_startup_override_or_test_profile() {
        LaunchMode previous = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            SmallRyeConfig prod = prodConfig(namedOverride("file:/tmp/startup-override123/application.properties"));
            assertProdPolicy(prod);
            prod = prodConfig(namedOverride("file:/tmp/test-profile123/application.properties"));
            assertProdPolicy(prod);
        } finally {
            LaunchMode.set(previous);
        }
    }

    @Test
    void prod_ignores_an_interceptor_at_application_priority() {
        LaunchMode previous = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            SmallRyeConfig prod = new SmallRyeConfigBuilder()
                    .addDefaultSources()
                    .addDiscoveredSources()
                    .addDefaultInterceptors()
                    .addDiscoveredInterceptors()
                    .withInterceptors(new ApplicationPriorityOverride())
                    .withProfile("prod")
                    .build();
            assertEquals("ran", prod.getConfigValue("auth.policy.lock.probe").getValue());
            assertProdPolicy(prod);
            assertTrue(AuthPolicyLockGuard.PRIORITY > Priorities.APPLICATION);
            assertTrue(AuthPolicyLockRecord.PRIORITY < Priorities.APPLICATION);
            assertTrue(AuthPolicyLockRecord.PRIORITY > Priorities.LIBRARY + 300);
        } finally {
            LaunchMode.set(previous);
        }
    }

    @Test
    void test_launch_mode_still_accepts_a_test_profile_source() {
        LaunchMode previous = LaunchMode.current();
        LaunchMode.set(LaunchMode.TEST);
        try {
            SmallRyeConfig prod = prodConfig(namedOverride("file:/tmp/test-profile123/application.properties"));
            assertEquals("false", prod.getConfigValue("quarkus.oidc.enabled").getValue());
            assertEquals("permit", prod.getConfigValue("quarkus.http.auth.permission.appliances.policy").getValue());
            assertEquals("100000", prod.getConfigValue("freedriver.appliances.rate-limit.permits").getValue());
        } finally {
            LaunchMode.set(previous);
        }
    }

    @Test
    void other_properties_files_omit_config_ordinal_and_auth_policy_keys() throws IOException {
        Set<String> owned = bareKeys(Path.of("src/main/resources/auth-policy.properties"));
        assertTrue(owned.contains("quarkus.oidc.enabled"));
        assertTrue(owned.contains("quarkus.http.auth.permission.appliances.policy"));
        assertTrue(owned.contains("quarkus.http.auth.permission.changelog.policy"));
        assertTrue(owned.contains("freedriver.appliances.rate-limit.permits"));
        assertTrue(owned.contains("freedriver.appliances.rate-limit.window"));
        Set<String> seen = new HashSet<>();
        try (Stream<Path> walk = Files.walk(Path.of("."))) {
            List<Path> files = walk.filter(AuthPolicyProdLockTest::isOtherPropertiesFile).toList();
            for (Path file : files) {
                seen.add(file.getFileName().toString());
                for (String line : Files.readAllLines(file)) {
                    String key = propertyName(line);
                    if (key == null) {
                        continue;
                    }
                    String bare = AuthPolicyLock.bareKey(key);
                    assertFalse("config_ordinal".equals(bare), file + " sets config_ordinal");
                    assertFalse(owned.contains(bare), file + " sets " + key);
                }
            }
        }
        assertTrue(seen.contains("application.properties"), seen.toString());
        assertTrue(seen.contains("application-prod.properties"), seen.toString());
        assertTrue(seen.contains("maven-wrapper.properties"), seen.toString());
    }

    @Test
    void service_files_are_exact_registrations_and_code_owned() throws IOException {
        Path services = Path.of("src/main/resources/META-INF/services");
        assertEquals(
                "io.freedriver.app.security.AuthPolicyConfigSource",
                Files.readString(services.resolve("org.eclipse.microprofile.config.spi.ConfigSource")).trim());
        assertEquals(
                Set.of(
                        "io.freedriver.app.security.AuthPolicyLockInterceptor",
                        "io.freedriver.app.security.AuthPolicyLockRecord",
                        "io.freedriver.app.security.AuthPolicyLockGuard"),
                Files.readAllLines(services.resolve("io.smallrye.config.ConfigSourceInterceptor")).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .collect(Collectors.toSet()));
        for (String forbidden : List.of(
                "org.eclipse.microprofile.config.spi.ConfigSourceProvider",
                "io.smallrye.config.ConfigSourceFactory",
                "io.smallrye.config.ConfigSourceInterceptorFactory",
                "io.smallrye.config.SmallRyeConfigBuilderCustomizer")) {
            assertFalse(Files.exists(services.resolve(forbidden)), forbidden);
        }
        String owners = Files.readString(Path.of("../../.github/CODEOWNERS"));
        assertTrue(owners.contains(
                "/java/dashboard/src/main/resources/META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource @kazetsukaimiko"));
        assertTrue(owners.contains(
                "/java/dashboard/src/main/resources/META-INF/services/io.smallrye.config.ConfigSourceInterceptor @kazetsukaimiko"));
        assertTrue(owners.contains("/java/dashboard/src/main/resources/auth-policy.properties @kazetsukaimiko"));
        assertTrue(owners.contains("/java/dashboard/src/test/java/io/freedriver/app/security/ @kazetsukaimiko"));
    }

    private static SmallRyeConfig prodConfig(ConfigSource... extra) {
        return new SmallRyeConfigBuilder()
                .addDefaultSources()
                .addDiscoveredSources()
                .addDefaultInterceptors()
                .addDiscoveredInterceptors()
                .withProfile("prod")
                .withSources(extra)
                .build();
    }

    private static ConfigSource propertiesFileAboveThePolicy() {
        Map<String, String> values = new HashMap<>();
        values.put("config_ordinal", "8000");
        values.put("quarkus.oidc.enabled", "false");
        values.put("%prod.quarkus.oidc.enabled", "false");
        values.put("quarkus.http.auth.permission.appliances.policy", "permit");
        values.put("quarkus.http.auth.permission.changelog.policy", "permit");
        values.put("quarkus.http.auth.permission.login.policy", "permit");
        values.put("quarkus.http.auth.permission.logout.policy", "permit");
        values.put("freedriver.appliances.rate-limit.permits", "100000");
        values.put("freedriver.appliances.rate-limit.window", "1s");
        values.put("quarkus.oidc.credentials.secret", "secret");
        return new PropertiesConfigSource(values, "application-prod.properties", 251);
    }

    private static ConfigSource jarAboveThePolicy() {
        Map<String, String> values = Map.of(
                "quarkus.oidc.enabled", "false",
                "%prod.quarkus.oidc.enabled", "false",
                "quarkus.http.auth.permission.appliances.policy", "permit",
                "freedriver.appliances.rate-limit.permits", "100000");
        return new ConfigSource() {
            @Override
            public Set<String> getPropertyNames() {
                return values.keySet();
            }

            @Override
            public String getValue(String propertyName) {
                return values.get(propertyName);
            }

            @Override
            public String getName() {
                return "hostile-jar";
            }

            @Override
            public int getOrdinal() {
                return 9000;
            }
        };
    }

    private static ConfigSource namedOverride(String sourceName) {
        Map<String, String> values = new HashMap<>();
        values.put("config_ordinal", Integer.toString(Integer.MAX_VALUE - 500));
        values.put("quarkus.oidc.enabled", "false");
        values.put("%prod.quarkus.oidc.enabled", "false");
        values.put("quarkus.http.auth.permission.appliances.policy", "permit");
        values.put("quarkus.http.auth.permission.changelog.policy", "permit");
        values.put("quarkus.http.auth.permission.login.policy", "permit");
        values.put("quarkus.http.auth.permission.logout.policy", "permit");
        values.put("freedriver.appliances.rate-limit.permits", "100000");
        values.put("freedriver.appliances.rate-limit.window", "1s");
        values.put("quarkus.oidc.credentials.secret", "secret");
        return new PropertiesConfigSource(values, sourceName, 100);
    }

    private static void assertProdPolicy(SmallRyeConfig prod) {
        assertEquals("true", prod.getConfigValue("quarkus.oidc.enabled").getValue());
        assertEquals(
                "authenticated",
                prod.getConfigValue("quarkus.http.auth.permission.appliances.policy").getValue());
        assertEquals(
                "authenticated",
                prod.getConfigValue("quarkus.http.auth.permission.changelog.policy").getValue());
        assertEquals(
                "authenticated",
                prod.getConfigValue("quarkus.http.auth.permission.login.policy").getValue());
        assertEquals(
                "authenticated",
                prod.getConfigValue("quarkus.http.auth.permission.logout.policy").getValue());
        assertEquals("30", prod.getConfigValue("freedriver.appliances.rate-limit.permits").getValue());
        assertEquals("60s", prod.getConfigValue("freedriver.appliances.rate-limit.window").getValue());
        assertEquals(
                "${QUARKUS_OIDC_CREDENTIALS_SECRET}",
                prod.getConfigValue("quarkus.oidc.credentials.secret").getRawValue());
    }

    @Priority(Priorities.APPLICATION)
    public static final class ApplicationPriorityOverride implements ConfigSourceInterceptor {
        @Override
        public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
            if ("auth.policy.lock.probe".equals(name)) {
                return ConfigValue.builder().withName(name).withValue("ran").build();
            }
            ConfigValue resolved = context.proceed(name);
            if ("quarkus.oidc.enabled".equals(name)
                    || "quarkus.http.auth.permission.appliances.policy".equals(name)
                    || "quarkus.http.auth.permission.changelog.policy".equals(name)
                    || "quarkus.http.auth.permission.login.policy".equals(name)
                    || "quarkus.http.auth.permission.logout.policy".equals(name)
                    || "freedriver.appliances.rate-limit.permits".equals(name)
                    || "freedriver.appliances.rate-limit.window".equals(name)) {
                String replacement = "quarkus.oidc.enabled".equals(name) ? "false"
                        : name.endsWith(".permits") ? "100000"
                        : name.endsWith(".window") ? "1s"
                        : "permit";
                return ConfigValue.builder()
                        .withName(name)
                        .withValue(replacement)
                        .withConfigSourceName("application-priority")
                        .withConfigSourceOrdinal(Priorities.APPLICATION)
                        .build();
            }
            return resolved;
        }
    }

    private static Set<String> bareKeys(Path file) throws IOException {
        Set<String> bare = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            String key = propertyName(line);
            if (key != null) {
                bare.add(AuthPolicyLock.bareKey(key));
            }
        }
        return bare;
    }

    private static boolean isOtherPropertiesFile(Path path) {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".properties")) {
            return false;
        }
        if (path.getFileName().toString().equals("auth-policy.properties")) {
            return false;
        }
        for (Path part : path) {
            String name = part.toString();
            if (name.equals("target") || name.equals("node_modules") || name.equals(".git")) {
                return false;
            }
        }
        return true;
    }

    private static String propertyName(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') {
            return null;
        }
        int cut = separator(trimmed);
        if (cut <= 0) {
            return null;
        }
        return trimmed.substring(0, cut).strip();
    }

    private static int separator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }
}
