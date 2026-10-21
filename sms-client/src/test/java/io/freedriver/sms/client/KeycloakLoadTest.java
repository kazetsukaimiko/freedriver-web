package io.freedriver.sms.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads sms-client the way a Keycloak 26.3 provider does: parent is the server's {@code lib/lib/main}
 * runtime, child is only the three modules' classes.
 *
 * <p>Set {@code KEYCLOAK_HOME} to a local Keycloak 26.3 directory (the one that contains
 * {@code version.txt} and {@code lib/lib/main}) to run this against that install. Otherwise the
 * test downloads {@code keycloak-quarkus-dist} 26.3.5 from Maven Central and checks the sha256.
 */
class KeycloakLoadTest {

    private static final String DIST_VERSION = "26.3.5";
    private static final String SHA256 = "95756bdfd7540fafaf28874a8eb8d0d4c55977344a3b7821e9bd3a22bfb2b6a6";
    private static final String DIST_URL =
            "https://repo1.maven.org/maven2/org/keycloak/keycloak-quarkus-dist/" + DIST_VERSION
                    + "/keycloak-quarkus-dist-" + DIST_VERSION + ".zip";

    @Test
    void client_loads_jackson_and_jax_rs_from_keycloak_runtime_not_from_the_modules() throws Exception {
        Path home = keycloakHome();
        String version = Files.readString(home.resolve("version.txt"));
        assertTrue(version.contains("26.3"), version);
        Path main = home.resolve("lib/lib/main");
        Path deployment = home.resolve("lib/lib/deployment");
        assertTrue(Files.isDirectory(main), main.toString());
        Path jackson = oneJar(main, "jackson-databind-");
        Path jaxrs = oneJar(main, "jakarta.ws.rs-api-");
        assertTrue(listJars(main, "jakarta.validation-api-").isEmpty(),
                "Keycloak runtime must not gain a second validation-api without this test being updated");
        Path validationAtBuild = oneJar(deployment, "jakarta.validation-api-");
        assertTrue(validationAtBuild.getFileName().toString().contains("3.0.2"), validationAtBuild.toString());

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
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("jakarta.validation.constraints.NotNull", false, provider));
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
        assertFalse(location.contains("sms-model"), location);
        assertFalse(location.contains("sms-client"), location);
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

    private static Path keycloakHome() throws Exception {
        String configured = System.getenv("KEYCLOAK_HOME");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path zip = Path.of("target/keycloak-quarkus-dist-" + DIST_VERSION + ".zip");
        if (!Files.isRegularFile(zip) || !SHA256.equals(sha256(zip))) {
            download(zip);
        }
        Path unpacked = Path.of("target/keycloak-" + DIST_VERSION);
        Path home = unpacked.resolve("keycloak-" + DIST_VERSION);
        if (!Files.isRegularFile(home.resolve("version.txt"))) {
            unzip(zip, unpacked);
        }
        return home;
    }

    private static void download(Path zip) throws Exception {
        Files.createDirectories(zip.getParent());
        Path partial = zip.resolveSibling(zip.getFileName() + ".partial");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(DIST_URL))
                .timeout(Duration.ofMinutes(5))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("keycloak dist status " + response.statusCode());
        }
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            in.transferTo(out);
        }
        String actual = sha256(partial);
        if (!SHA256.equals(actual)) {
            Files.deleteIfExists(partial);
            throw new IOException("keycloak dist sha256 mismatch");
        }
        Files.move(partial, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        if (Files.exists(dest)) {
            try (Stream<Path> walk = Files.walk(dest)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(dest);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("zip entry escapes target");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target);
                }
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
