package io.freedriver.app.security;

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
import java.util.Set;
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
        var oidc = prod.getConfigValue("quarkus.oidc.enabled");
        assertEquals("true", oidc.getValue());
        assertEquals(AuthPolicyConfigSource.RESOURCE, oidc.getConfigSourceName());
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

    @Test
    void quarkus_test_profile_source_still_supplies_oidc() {
        SmallRyeConfig prod = prodConfig(testProfileOverride());
        assertEquals("false", prod.getConfigValue("quarkus.oidc.enabled").getValue());
        assertTrue(prod.getConfigValue("quarkus.oidc.enabled").getConfigSourceName().contains("test-profile"));
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
                    String bare = AuthPolicyLockInterceptor.bareKey(key);
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
                "io.freedriver.app.security.AuthPolicyLockInterceptor",
                Files.readString(services.resolve("io.smallrye.config.ConfigSourceInterceptor")).trim());
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

    private static ConfigSource testProfileOverride() {
        Map<String, String> values = new HashMap<>();
        values.put("config_ordinal", Integer.toString(Integer.MAX_VALUE - 500));
        values.put("quarkus.oidc.enabled", "false");
        return new PropertiesConfigSource(
                values, "file:/tmp/test-profile123/application.properties", 100);
    }

    private static Set<String> bareKeys(Path file) throws IOException {
        Set<String> bare = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            String key = propertyName(line);
            if (key != null) {
                bare.add(AuthPolicyLockInterceptor.bareKey(key));
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
