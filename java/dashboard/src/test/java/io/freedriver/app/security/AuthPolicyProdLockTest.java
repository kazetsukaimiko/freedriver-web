package io.freedriver.app.security;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigSourceInterceptorFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void prod_keeps_built_in_defaults_for_permissions_and_tenants() {
        LaunchMode previous = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            assertProdPolicy(prodConfig());
            assertProdPolicy(prodConfig(spoofedDefaults("DefaultValuesConfigSource", Integer.MIN_VALUE)));
            assertProdPolicy(prodConfig(spoofedDefaults(
                    "io.smallrye.config.DefaultValuesConfigSource", Integer.MAX_VALUE - 500)));
        } finally {
            LaunchMode.set(previous);
        }
    }

    @Test
    void prod_ignores_an_interceptor_above_the_profile_chain() {
        LaunchMode previous = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            assertEquals(Integer.MAX_VALUE, AuthPolicyLockGuard.PRIORITY);
            for (int priority : List.of(
                    Priorities.APPLICATION,
                    Priorities.APPLICATION + 2,
                    Priorities.LIBRARY + 300,
                    Priorities.LIBRARY + 400,
                    Integer.MAX_VALUE - 1)) {
                SmallRyeConfig prod = prodConfig(override(priority, true));
                assertEquals("ran", prod.getConfigValue("auth.policy.lock.probe").getValue(), Integer.toString(priority));
                assertProdPolicy(prod);
                prod = prodConfig(override(priority, false));
                assertProdPolicy(prod);
            }
            SmallRyeConfig atMax = prodConfig(override(Integer.MAX_VALUE, false));
            assertNull(atMax.getConfigValue("auth.policy.lock.probe").getValue());
            assertProdPolicy(atMax);
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
                    assertFalse(
                            "config_ordinal".equals(bare) || AuthPolicyLock.locksBare(bare, owned),
                            file + " sets " + key);
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
                        "io.freedriver.app.security.AuthPolicyLockGuard"),
                serviceNames(services.resolve("io.smallrye.config.ConfigSourceInterceptor")));
        assertEquals(
                Set.of("io.freedriver.app.security.AuthPolicyLockCustomizer"),
                serviceNames(services.resolve("io.smallrye.config.SmallRyeConfigBuilderCustomizer")));
        for (String forbidden : List.of(
                "org.eclipse.microprofile.config.spi.ConfigSourceProvider",
                "org.eclipse.microprofile.config.spi.Converter",
                "io.smallrye.config.ConfigSourceFactory",
                "io.smallrye.config.ConfigSourceInterceptorFactory",
                "io.smallrye.config.SecretKeysHandler",
                "io.smallrye.config.SecretKeysHandlerFactory")) {
            assertFalse(Files.exists(services.resolve(forbidden)), forbidden);
        }
        String owners = Files.readString(Path.of("../../.github/CODEOWNERS"));
        assertTrue(owners.contains(
                "/java/dashboard/src/main/resources/META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource @kazetsukaimiko"));
        assertTrue(owners.contains(
                "/java/dashboard/src/main/resources/META-INF/services/io.smallrye.config.ConfigSourceInterceptor @kazetsukaimiko"));
        assertTrue(owners.contains(
                "/java/dashboard/src/main/resources/META-INF/services/io.smallrye.config.SmallRyeConfigBuilderCustomizer @kazetsukaimiko"));
        assertTrue(owners.contains("/java/dashboard/src/main/resources/auth-policy.properties @kazetsukaimiko"));
        assertTrue(owners.contains("/java/dashboard/src/test/java/io/freedriver/app/security/ @kazetsukaimiko"));
    }

    private static SmallRyeConfig prodConfig(ConfigSource... extra) {
        return configBuilder().withSources(extra).build();
    }

    private static SmallRyeConfig prodConfig(ConfigSourceInterceptorFactory override) {
        return configBuilder().withInterceptorFactories(override).build();
    }

    private static SmallRyeConfigBuilder configBuilder() {
        return new SmallRyeConfigBuilder()
                .addDefaultSources()
                .addDiscoveredSources()
                .addDefaultInterceptors()
                .addDiscoveredInterceptors()
                .withCustomizers(new AuthPolicyLockCustomizer())
                .withProfile("prod");
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
        hostileDefaults(values);
        return new PropertiesConfigSource(values, "application-prod.properties", 251);
    }

    private static ConfigSource jarAboveThePolicy() {
        Map<String, String> values = new HashMap<>();
        values.put("quarkus.oidc.enabled", "false");
        values.put("%prod.quarkus.oidc.enabled", "false");
        values.put("quarkus.http.auth.permission.appliances.policy", "permit");
        values.put("freedriver.appliances.rate-limit.permits", "100000");
        hostileDefaults(values);
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

    private static ConfigSource spoofedDefaults(String sourceName, int ordinal) {
        Map<String, String> values = new HashMap<>();
        values.put("quarkus.oidc.enabled", "false");
        values.put("%prod.quarkus.oidc.enabled", "false");
        values.put("quarkus.http.auth.permission.appliances.policy", "permit");
        values.put("freedriver.appliances.rate-limit.permits", "100000");
        hostileDefaults(values);
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
                return sourceName;
            }

            @Override
            public int getOrdinal() {
                return ordinal;
            }
        };
    }

    /** Values a hostile source would use to turn OIDC off or permit appliance routes. */
    private static void hostileDefaults(Map<String, String> values) {
        values.put("quarkus.oidc.tenant-enabled", "false");
        values.put("quarkus.oidc.*.tenant-enabled", "false");
        values.put("quarkus.oidc.backdoor.tenant-enabled", "false");
        values.put("quarkus.http.auth.permission.appliances.shared", "true");
        values.put("quarkus.http.auth.permission.public.shared", "true");
        values.put("quarkus.http.auth.permission.*.shared", "true");
        values.put("quarkus.http.auth.permission.appliances.applies-to", "JAXRS");
        values.put("quarkus.http.auth.permission.*.applies-to", "JAXRS");
        values.put("quarkus.http.auth.permission.*.policy", "permit");
        values.put("quarkus.http.auth.permission.*.paths", "/*");
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
        values.put("quarkus.http.auth.permission.appliances.enabled", "false");
        values.put("quarkus.http.auth.permission.open-post.paths", "/api/appliances/*");
        values.put("quarkus.http.auth.permission.open-post.methods", "POST");
        values.put("quarkus.http.auth.permission.open-post.policy", "permit");
        hostileDefaults(values);
        return new PropertiesConfigSource(values, sourceName, 100);
    }

    private static void assertProdPolicy(SmallRyeConfig prod) {
        assertEquals("true", prod.getConfigValue("quarkus.oidc.enabled").getValue());
        assertEquals(
                AuthPolicyConfigSource.RESOURCE,
                prod.getConfigValue("quarkus.oidc.enabled").getConfigSourceName());
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
        assertAbsent(prod, "quarkus.http.auth.permission.appliances.enabled");
        assertAbsent(prod, "quarkus.http.auth.permission.open-post.paths");
        assertAbsent(prod, "quarkus.http.auth.permission.open-post.methods");
        assertAbsent(prod, "quarkus.http.auth.permission.open-post.policy");
        assertBuiltInDefaults(prod);
        assertSecret(prod);
    }

    private static void assertBuiltInDefaults(SmallRyeConfig prod) {
        assertEquals("true", prod.getConfigValue("quarkus.oidc.tenant-enabled").getValue());
        assertEquals(
                AuthPolicyConfigSource.RESOURCE,
                prod.getConfigValue("quarkus.oidc.tenant-enabled").getConfigSourceName());
        assertEquals("true", prod.getConfigValue("quarkus.oidc.*.tenant-enabled").getValue());
        assertEquals("true", prod.getConfigValue("quarkus.oidc.backdoor.tenant-enabled").getValue());
        assertEquals("false", prod.getConfigValue("quarkus.http.auth.permission.appliances.shared").getValue());
        assertEquals("false", prod.getConfigValue("quarkus.http.auth.permission.public.shared").getValue());
        assertEquals("false", prod.getConfigValue("quarkus.http.auth.permission.*.shared").getValue());
        assertEquals("ALL", prod.getConfigValue("quarkus.http.auth.permission.appliances.applies-to").getValue());
        assertEquals("ALL", prod.getConfigValue("quarkus.http.auth.permission.*.applies-to").getValue());
        assertAbsent(prod, "quarkus.http.auth.permission.*.policy");
        assertAbsent(prod, "quarkus.http.auth.permission.*.paths");
    }

    private static void assertAbsent(SmallRyeConfig prod, String name) {
        ConfigValue value = prod.getConfigValue(name);
        assertTrue(value == null || value.getValue() == null, name + "=" + (value == null ? null : value.getValue()));
    }

    private static void assertSecret(SmallRyeConfig prod) {
        String key = "QUARKUS_OIDC_CREDENTIALS_SECRET";
        String previous = System.getProperty(key);
        System.setProperty(key, "from-test");
        try {
            ConfigValue secret = prod.getConfigValue("quarkus.oidc.credentials.secret");
            assertEquals("${QUARKUS_OIDC_CREDENTIALS_SECRET}", secret.getRawValue());
            assertEquals("from-test", secret.getValue());
            assertFalse(secret.hasProblems());
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void prod_secret_stays_an_expression_and_fails_closed_when_unset() {
        String key = "QUARKUS_OIDC_CREDENTIALS_SECRET";
        if (System.getenv(key) != null || System.getProperty(key) != null) {
            return;
        }
        LaunchMode previous = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);
        try {
            SmallRyeConfig prod = prodConfig();
            ConfigValue unset = prod.getConfigValue("quarkus.oidc.credentials.secret");
            assertEquals("${QUARKUS_OIDC_CREDENTIALS_SECRET}", unset.getRawValue());
            assertTrue(unset.hasProblems());
            assertThrows(
                    NoSuchElementException.class,
                    () -> prod.getValue("quarkus.oidc.credentials.secret", String.class));
        } finally {
            LaunchMode.set(previous);
        }
    }

    private static ConfigSourceInterceptorFactory override(int priority, boolean proceed) {
        return new ConfigSourceInterceptorFactory() {
            @Override
            public ConfigSourceInterceptor getInterceptor(ConfigSourceInterceptorContext context) {
                return new PolicyReplacer(proceed);
            }

            @Override
            public OptionalInt getPriority() {
                return OptionalInt.of(priority);
            }
        };
    }

    private static final class PolicyReplacer implements ConfigSourceInterceptor {
        private final boolean proceed;

        private PolicyReplacer(boolean proceed) {
            this.proceed = proceed;
        }

        @Override
        public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
            if ("auth.policy.lock.probe".equals(name)) {
                return ConfigValue.builder().withName(name).withValue("ran").build();
            }
            ConfigValue resolved = proceed ? context.proceed(name) : null;
            if ("quarkus.oidc.enabled".equals(name)
                    || "quarkus.http.auth.permission.appliances.policy".equals(name)
                    || "quarkus.http.auth.permission.appliances.enabled".equals(name)
                    || "quarkus.http.auth.permission.appliances.shared".equals(name)
                    || "quarkus.http.auth.permission.appliances.applies-to".equals(name)
                    || "quarkus.http.auth.permission.public.shared".equals(name)
                    || "quarkus.http.auth.permission.*.shared".equals(name)
                    || "quarkus.http.auth.permission.*.applies-to".equals(name)
                    || "quarkus.http.auth.permission.*.policy".equals(name)
                    || "quarkus.http.auth.permission.*.paths".equals(name)
                    || "quarkus.http.auth.permission.changelog.policy".equals(name)
                    || "quarkus.http.auth.permission.login.policy".equals(name)
                    || "quarkus.http.auth.permission.logout.policy".equals(name)
                    || "quarkus.http.auth.permission.open-post.policy".equals(name)
                    || "quarkus.http.auth.permission.open-post.paths".equals(name)
                    || "quarkus.http.auth.permission.open-post.methods".equals(name)
                    || "quarkus.oidc.tenant-enabled".equals(name)
                    || "quarkus.oidc.*.tenant-enabled".equals(name)
                    || "quarkus.oidc.backdoor.tenant-enabled".equals(name)
                    || "quarkus.oidc.credentials.secret".equals(name)
                    || "freedriver.appliances.rate-limit.permits".equals(name)
                    || "freedriver.appliances.rate-limit.window".equals(name)) {
                String replacement = "quarkus.oidc.enabled".equals(name) || name.endsWith("tenant-enabled")
                        || name.endsWith(".enabled") ? "false"
                        : name.endsWith(".permits") ? "100000"
                        : name.endsWith(".window") ? "1s"
                        : name.endsWith(".secret") ? "secret"
                        : name.endsWith(".paths") ? "/api/appliances/*"
                        : name.endsWith(".methods") ? "POST"
                        : name.endsWith(".shared") ? "true"
                        : name.endsWith(".applies-to") ? "JAXRS"
                        : "permit";
                return ConfigValue.builder()
                        .withName(name)
                        .withValue(replacement)
                        .withRawValue(replacement)
                        .withConfigSourceName("application-priority")
                        .withConfigSourceOrdinal(priorityOrdinal())
                        .build();
            }
            return resolved != null ? resolved : context.proceed(name);
        }

        private static int priorityOrdinal() {
            return Priorities.APPLICATION;
        }
    }

    private static Set<String> serviceNames(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && line.charAt(0) != '#')
                .collect(Collectors.toSet());
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
