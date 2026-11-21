package io.freedriver.sms.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads sms-client the way a Keycloak 26.7 provider does: parent is the server's {@code lib/lib/main}
 * runtime, child is only the three modules' classes.
 *
 * <p>Set {@code KEYCLOAK_HOME} to a local Keycloak 26.7 directory (the one that contains
 * {@code version.txt} and {@code lib/lib/main}) to run this against that install. Otherwise Maven
 * unpacks the distribution and surefire passes that directory as {@code keycloak.home}.
 */
class KeycloakLoadTest {

    @Test
    void client_loads_jackson_and_jax_rs_from_keycloak_runtime_not_from_the_modules() throws Exception {
        Path home = keycloakHome();
        String version = Files.readString(home.resolve("version.txt"));
        assertTrue(version.contains("26.7"), version);
        Path main = home.resolve("lib/lib/main");
        Path deployment = home.resolve("lib/lib/deployment");
        assertTrue(Files.isDirectory(main), main.toString());
        Path jackson = oneJar(main, "jackson-databind-");
        Path jaxrs = oneJar(main, "jakarta.ws.rs-api-");
        Path validation = oneJar(main, "jakarta.validation-api-");
        assertTrue(jackson.getFileName().toString().contains("2.21.7"), jackson.toString());
        assertTrue(validation.getFileName().toString().contains("3.1.1"), validation.toString());
        assertTrue(listJars(deployment, "jakarta.validation-api-").isEmpty(),
                deployment + " should not also ship validation-api");

        List<URL> runtime = new ArrayList<>();
        try (Stream<Path> jars = Files.list(main)) {
            for (Path jar : jars.filter(path -> path.toString().endsWith(".jar")).toList()) {
                runtime.add(jar.toUri().toURL());
            }
        }
        URL[] modules = {
                classes("sms-model"),
                classes("sms-api"),
                classes("sms-client")
        };
        String secret = "keycloak-load-test-secret";
        try (URLClassLoader keycloak = new URLClassLoader(runtime.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
             URLClassLoader provider = new URLClassLoader(modules, keycloak)) {
            Class<?> clientType = Class.forName("io.freedriver.sms.client.SmsClient", true, provider);
            Class<?> configType = Class.forName("io.freedriver.sms.client.SmsClientConfig", true, provider);
            Class<?> mapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, provider);
            Class<?> path = Class.forName("jakarta.ws.rs.Path", false, provider);
            assertFromRuntime(mapper, "jackson-databind");
            assertFromRuntime(path, "jakarta.ws.rs-api");
            Class<?> notNull = Class.forName("jakarta.validation.constraints.NotNull", false, provider);
            assertFromRuntime(notNull, "jakarta.validation-api");
            // jakarta.ws.rs-api contains the client interfaces. A client runtime is a different jar.
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("org.jboss.resteasy.client.jaxrs.ResteasyClient", false, provider));
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("org.glassfish.jersey.client.JerseyClient", false, provider));

            Object config = configType.getConstructor(URI.class, String.class)
                    .newInstance(URI.create("http://sms:8080"), secret);
            Object proxy = clientType.getMethod("create", configType).invoke(null, config);
            assertTrue(Proxy.isProxyClass(proxy.getClass()));
            String printed = (String) proxy.getClass().getMethod("toString").invoke(proxy);
            assertFalse(printed.contains(secret));
            assertFalse(config.toString().contains(secret));
            assertFromModule(clientType, "sms-client");

            Class<?> request = Class.forName("io.freedriver.sms.model.OtpSendRequest", true, provider);
            Class<?> shown = Class.forName("io.freedriver.sms.model.AgreementShown", true, provider);
            assertFromModule(request, "sms-model");
            Object body = request.getConstructor(String.class, shown).newInstance("+15555550100", null);
            Object objectMapper = mapper.getConstructor().newInstance();
            byte[] json = (byte[]) mapper.getMethod("writeValueAsBytes", Object.class).invoke(objectMapper, body);
            String text = new String(json, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("\"type\":\"otp\""), text);
            assertTrue(text.contains("+15555550100"), text);
            assertNotNull(jackson);
            assertNotNull(jaxrs);
        }
    }

    private static void assertFromRuntime(Class<?> type, String jarName) {
        String location = source(type);
        assertTrue(location.contains("/lib/lib/main/"), location);
        assertTrue(location.contains(jarName), location);
        // The dist may be unpacked under sms-client/target, so match the module output, not the name.
        assertFalse(location.contains("/target/classes"), location);
    }

    private static void assertFromModule(Class<?> type, String module) {
        String location = source(type);
        assertTrue(location.contains(module), location);
        assertFalse(location.contains("/lib/lib/main/"), location);
    }

    private static String source(Class<?> type) {
        var domain = type.getProtectionDomain();
        assertNotNull(domain);
        var code = domain.getCodeSource();
        assertNotNull(code, type.getName());
        return code.getLocation().toString();
    }

    private static URL classes(String module) throws IOException {
        Path dir = Path.of("").toAbsolutePath().resolve("..").resolve(module).resolve("target/classes").normalize();
        assertTrue(Files.isDirectory(dir), dir.toString());
        return dir.toUri().toURL();
    }

    private static Path keycloakHome() throws IOException {
        String configured = System.getenv("KEYCLOAK_HOME");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String property = System.getProperty("keycloak.home");
        assertNotNull(property, "keycloak.home");
        Path home = Path.of(property);
        Path parent = home.getParent();
        assertNotNull(parent, home.toString());
        List<Path> versions;
        try (Stream<Path> walk = Files.walk(parent)) {
            versions = walk.filter(Files::isRegularFile)
                    .filter(path -> "version.txt".equals(path.getFileName().toString()))
                    .toList();
        }
        assertTrue(versions.size() == 1, parent + " version.txt -> " + versions);
        return home;
    }

    private static Path oneJar(Path dir, String prefix) throws IOException {
        List<Path> jars = listJars(dir, prefix);
        assertEqualsOne(jars, dir, prefix);
        return jars.get(0);
    }

    private static void assertEqualsOne(List<Path> jars, Path dir, String prefix) {
        assertTrue(jars.size() == 1, dir + " " + prefix + " -> " + jars);
    }

    private static List<Path> listJars(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().contains(prefix)
                    && path.toString().endsWith(".jar")).toList();
        }
    }
}
