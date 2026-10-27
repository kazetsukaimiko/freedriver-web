package io.freedriver.app.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.mqtt.MqttEndpoint;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.paho.PahoMqttConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Provisions two houses on Mosquitto 2.1.2 dynamic security and checks the stock config.
 * Docker uses eclipse-mosquitto:2.1.2-alpine. Without docker, set MOSQUITTO_PREFIX.
 */
class HouseProvisionerTest {

    private static final UUID HOUSE_A = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID HOUSE_B = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
    private static final String ADMIN_USER = "freedriver-dynsec";
    private static final String API_USER = "api";
    private static final String IMAGE = "eclipse-mosquitto:2.1.2-alpine";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FORBIDDEN_ROLES = Set.of(
            "admin", "dynsec-admin", "super-admin", "broker-admin", "client");

    @Test
    void stockConfigLoadsThePluginAndDropsPasswordFiles() throws IOException {
        List<String> lines = activeLines(Path.of("..", "..", "mosquitto", "mosquitto.conf"));
        int firstListener = indexOfPrefix(lines, "listener ");
        assertTrue(firstListener > 0, "listeners missing");
        List<String> globals = lines.subList(0, firstListener);
        assertTrue(globals.contains("allow_anonymous false"));
        assertTrue(globals.contains("retain_available false"));
        assertTrue(globals.contains("plugin /usr/lib/mosquitto_dynamic_security.so"));
        assertTrue(globals.contains("plugin_opt_config_file /mosquitto/data/dynamic-security.json"));
        assertTrue(globals.contains(
                "plugin_opt_password_init_file /mosquitto/config/secrets/dynsec-password-init"));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("password_file ")
                || line.startsWith("acl_file ")
                || line.startsWith("per_listener_settings ")
                || line.startsWith("bind_address ")));
        List<String> ports = new ArrayList<>();
        String internal = "";
        String published = "";
        StringBuilder current = null;
        String currentPort = null;
        for (int i = firstListener; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("listener ")) {
                if (current != null) {
                    if ("8883".equals(currentPort)) {
                        internal = current.toString();
                    } else if ("8884".equals(currentPort)) {
                        published = current.toString();
                    }
                }
                currentPort = line.substring("listener ".length()).strip();
                ports.add(currentPort);
                current = new StringBuilder();
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            if ("8883".equals(currentPort)) {
                internal = current.toString();
            } else if ("8884".equals(currentPort)) {
                published = current.toString();
            }
        }
        assertEquals(List.of("8883", "8884"), ports);
        assertTrue(internal.contains("certfile /mosquitto/config/secrets/internal.crt"));
        assertTrue(internal.contains("keyfile /mosquitto/config/secrets/internal.key"));
        assertFalse(internal.contains("server.crt") || internal.contains("server.key"));
        assertTrue(published.contains("certfile /mosquitto/config/secrets/server.crt"));
        assertTrue(published.contains("keyfile /mosquitto/config/secrets/server.key"));
        assertFalse(published.contains("internal."));
    }

    @Test
    void rejectsTheDefaultAdminLogin() {
        IllegalStateException blank = assertThrows(IllegalStateException.class, () -> HouseProvisioner.provisionHouse(
                HOUSE_A, "127.0.0.1", 8883, true, Path.of("unused-trust"),
                "", "", API_USER, "api-secret"));
        assertEquals("dynamic security admin is not configured", blank.getMessage());

        IllegalStateException defaultUser = assertThrows(IllegalStateException.class,
                () -> HouseProvisioner.provisionHouse(
                        HOUSE_A, "127.0.0.1", 8883, true, Path.of("unused-trust"),
                        "admin", "not-shown-secret", API_USER, "api-secret"));
        assertEquals("dynamic security admin login must not be the default", defaultUser.getMessage());
        assertFalse(defaultUser.getMessage().contains("not-shown-secret"));

        IllegalStateException defaultPassword = assertThrows(IllegalStateException.class,
                () -> HouseProvisioner.provisionHouse(
                        HOUSE_A, "127.0.0.1", 8883, true, Path.of("unused-trust"),
                        ADMIN_USER, "admin", API_USER, "api-secret"));
        assertEquals("dynamic security admin login must not be the default", defaultPassword.getMessage());
    }

    @Test
    void provisionedHouseToStringOmitsTheSecret() {
        ProvisionedHouse house = new ProvisionedHouse(
                HOUSE_A, HouseProvisioner.autonomyUsername(HOUSE_A), "broker-secret-omitted");
        String text = house.toString();
        assertTrue(text.contains(HOUSE_A.toString()));
        assertTrue(text.contains(house.autonomyUsername()));
        assertFalse(text.contains("broker-secret-omitted"));
        assertEquals(43, HouseProvisioner.newSecret().length());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void provisionCallLimitsEachHouseOnThePlugin() throws Exception {
        BrokerKind kind = BrokerKind.detect();
        assumeTrue(kind != BrokerKind.ABSENT, "no docker and no MOSQUITTO_PREFIX");
        if (kind == BrokerKind.REQUIRED) {
            fail("CI requires a Mosquitto dynamic-security broker");
        }
        assertPortFree(8883);
        assertPortFree(8884);

        String adminPassword = secret();
        String apiPassword = secret();
        assertNotEquals(adminPassword, apiPassword);
        Path work = Files.createTempDirectory("dynsec-provision");
        Path secrets = work.resolve("secrets");
        Path data = work.resolve("data");
        Files.createDirectories(secrets);
        Files.createDirectories(data);
        certificate(secrets.resolve("internal.crt"), secrets.resolve("internal.key"), "IP:127.0.0.1,DNS:mosquitto");
        certificate(secrets.resolve("server.crt"), secrets.resolve("server.key"), "DNS:mqtt.freedriver.io");
        readable(secrets);

        Broker broker = Broker.start(kind, work, secrets, data, false);
        try {
            broker.awaitListening();
            assertFalse(Files.exists(data.resolve("dynamic-security.json")));
            assertFalse(Files.exists(data.resolve("dynamic-security.json.pw")));
            String coldLog = broker.logs();
            assertTrue(coldLog.contains("Unable to get initial password"), "password-init file must block default admin");
            assertConnectFails("admin", "admin", secrets.resolve("internal.crt"));
            assertConnectFails(ADMIN_USER, adminPassword, secrets.resolve("internal.crt"));
        } finally {
            broker.close();
        }

        initDynsec(kind, data, adminPassword);
        assertTrue(Files.size(data.resolve("dynamic-security.json")) > 0);
        broker = Broker.start(kind, work, secrets, data, true);
        ProvisionedHouse first;
        ProvisionedHouse second;
        try {
            broker.awaitListening();
            first = HouseProvisioner.provisionHouse(
                    HOUSE_A, "127.0.0.1", 8883, true, secrets.resolve("internal.crt"),
                    ADMIN_USER, adminPassword, API_USER, apiPassword);
            String afterFirst = awaitJson(broker, data, first.autonomyUsername());
            String apiHash = encodedPassword(afterFirst, API_USER);
            assertFalse(apiHash.isBlank());

            second = HouseProvisioner.provisionHouse(
                    HOUSE_B, "127.0.0.1", 8883, true, secrets.resolve("internal.crt"),
                    ADMIN_USER, adminPassword, API_USER, apiPassword);
            String saved = awaitJson(broker, data, second.autonomyUsername());
            assertEquals(apiHash, encodedPassword(saved, API_USER));
            assertHouseFile(saved, first, second, adminPassword, apiPassword);

            String appliancesA = ApplianceSchemas.appliancesTopic(HOUSE_A);
            String commandsA = ApplianceSchemas.commandsTopic(HOUSE_A);
            String appliancesB = ApplianceSchemas.appliancesTopic(HOUSE_B);
            Path trust = secrets.resolve("internal.crt");
            assertDelivered(first.autonomyUsername(), first.brokerSecret(), appliancesA, "state-a",
                    API_USER, apiPassword, appliancesA, trust);
            assertDelivered(API_USER, apiPassword, commandsA, "cmd-a",
                    first.autonomyUsername(), first.brokerSecret(), commandsA, trust);
            assertNotDelivered(first.autonomyUsername(), first.brokerSecret(), appliancesB,
                    ADMIN_USER, adminPassword, appliancesB, trust);
            assertNotDelivered(first.autonomyUsername(), first.brokerSecret(), DynsecControl.COMMAND_TOPIC,
                    ADMIN_USER, adminPassword, DynsecControl.COMMAND_TOPIC, trust);
            assertNotDelivered(API_USER, apiPassword, DynsecControl.COMMAND_TOPIC,
                    ADMIN_USER, adminPassword, DynsecControl.COMMAND_TOPIC, trust);
            assertNotDelivered(first.autonomyUsername(), first.brokerSecret(), commandsA,
                    ADMIN_USER, adminPassword, commandsA, trust);
            assertNotDelivered(API_USER, apiPassword, appliancesA,
                    ADMIN_USER, adminPassword, appliancesA, trust);

            String logs = broker.logs();
            assertFalse(logs.contains(adminPassword));
            assertFalse(logs.contains(apiPassword));
            assertFalse(logs.contains(first.brokerSecret()));
            assertFalse(logs.contains(second.brokerSecret()));
            assertEquals(43, first.brokerSecret().length());
            assertEquals(43, second.brokerSecret().length());
            assertFalse(first.toString().contains(first.brokerSecret()));
            assertFalse(second.toString().contains(second.brokerSecret()));
            assertNotEquals(first.brokerSecret(), second.brokerSecret());
        } finally {
            broker.close();
            deleteTree(work);
        }
    }

    private static void assertHouseFile(
            String saved,
            ProvisionedHouse first,
            ProvisionedHouse second,
            String adminPassword,
            String apiPassword) throws IOException {
        assertFalse(saved.contains(adminPassword));
        assertFalse(saved.contains(apiPassword));
        assertFalse(saved.contains(first.brokerSecret()));
        assertFalse(saved.contains(second.brokerSecret()));
        JsonNode root = JSON.readTree(saved);
        Set<String> usernames = new HashSet<>();
        for (JsonNode client : root.path("clients")) {
            String username = client.path("username").asText();
            usernames.add(username);
            assertNotEquals("admin", username);
            if (username.startsWith("autonomy-") || API_USER.equals(username)) {
                for (JsonNode role : client.path("roles")) {
                    assertFalse(FORBIDDEN_ROLES.contains(role.path("rolename").asText()));
                }
            }
        }
        assertTrue(usernames.contains(ADMIN_USER));
        assertTrue(usernames.contains(API_USER));
        assertTrue(usernames.contains(first.autonomyUsername()));
        assertTrue(usernames.contains(second.autonomyUsername()));
        for (JsonNode role : root.path("roles")) {
            String name = role.path("rolename").asText();
            if (API_USER.equals(name) || name.startsWith("autonomy-")) {
                assertTrue(role.has("allowwildcardsubs"));
                assertFalse(role.get("allowwildcardsubs").asBoolean());
                for (JsonNode acl : role.path("acls")) {
                    String topic = acl.path("topic").asText();
                    assertFalse(topic.contains("+") || topic.contains("#"), topic);
                }
            }
        }
    }

    private static void assertDelivered(
            String publisherUser,
            String publisherPassword,
            String publishTopic,
            String body,
            String subscriberUser,
            String subscriberPassword,
            String subscribeTopic,
            Path trust) throws Exception {
        try (Session subscriber = Session.open(subscriberUser, subscriberPassword, trust);
             Session publisher = Session.open(publisherUser, publisherPassword, trust)) {
            subscriber.listen(subscribeTopic);
            publisher.publish(publishTopic, body);
            String got = subscriber.poll(Duration.ofSeconds(5));
            assertNotNull(got);
            assertTrue(got.contains(body));
        }
    }

    private static void assertNotDelivered(
            String publisherUser,
            String publisherPassword,
            String publishTopic,
            String subscriberUser,
            String subscriberPassword,
            String subscribeTopic,
            Path trust) throws Exception {
        try (Session subscriber = Session.open(subscriberUser, subscriberPassword, trust);
             Session publisher = Session.open(publisherUser, publisherPassword, trust)) {
            subscriber.listen(subscribeTopic);
            try {
                publisher.publish(publishTopic, "denied");
            } catch (IllegalStateException refused) {
                assertEquals("Live MQTT publish failed", refused.getMessage());
            }
            assertNull(subscriber.poll(Duration.ofMillis(800)));
        }
    }

    private static void assertConnectFails(String username, String password, Path trust) {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            try (Session ignored = Session.open(username, password, trust)) {
                fail("connect succeeded");
            }
        });
        assertEquals("Live MQTT connect failed", ex.getMessage());
    }

    private static String awaitJson(Broker broker, Path data, String needle) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String lastError = "";
        while (System.nanoTime() < deadline) {
            try {
                String json = readDynsec(broker, data);
                if (json.contains(needle)) {
                    return json;
                }
            } catch (IOException | InterruptedException ex) {
                lastError = ex.getClass().getSimpleName();
            }
            Thread.sleep(200);
        }
        fail("dynamic-security file did not record the house (" + lastError + ")");
        return "";
    }

    private static String readDynsec(Broker broker, Path data) throws Exception {
        Path file = data.resolve("dynamic-security.json");
        if (Files.isRegularFile(file) && Files.isReadable(file) && Files.size(file) > 0) {
            return Files.readString(file);
        }
        if (broker.container != null) {
            return run(List.of("docker", "exec", broker.container, "cat", "/mosquitto/data/dynamic-security.json"), null)
                    .stdout;
        }
        throw new IOException("dynamic-security.json is not readable");
    }

    private static String encodedPassword(String json, String username) throws IOException {
        for (JsonNode client : JSON.readTree(json).path("clients")) {
            if (username.equals(client.path("username").asText())) {
                return client.path("encoded_password").asText("");
            }
        }
        return "";
    }

    private static void initDynsec(BrokerKind kind, Path data, String adminPassword) throws Exception {
        Path file = data.resolve("dynamic-security.json");
        List<String> command;
        if (kind == BrokerKind.DOCKER) {
            command = List.of(
                    "docker", "run", "--rm", "-i",
                    "-v", data.toAbsolutePath() + ":/mosquitto/data",
                    IMAGE,
                    "mosquitto_ctrl", "dynsec", "init",
                    "/mosquitto/data/dynamic-security.json",
                    ADMIN_USER);
        } else {
            command = List.of(
                    kind.ctrl.toString(), "dynsec", "init", file.toString(), ADMIN_USER);
        }
        CommandResult result = run(command, (adminPassword + "\n" + adminPassword + "\n").getBytes(StandardCharsets.UTF_8));
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            fail("mosquitto_ctrl dynsec init did not write the client file");
        }
        String written = kind == BrokerKind.DOCKER
                ? run(List.of(
                        "docker", "run", "--rm",
                        "-v", data.toAbsolutePath() + ":/mosquitto/data",
                        "--entrypoint", "/bin/cat",
                        IMAGE,
                        "/mosquitto/data/dynamic-security.json"), null).stdout
                : Files.readString(file);
        assertTrue(written.contains(ADMIN_USER));
        assertFalse(written.contains(adminPassword));
        assertFalse(result.stdout.contains(adminPassword));
        assertFalse(result.stderr.contains(adminPassword));
    }

    private static void certificate(Path crt, Path key, String san) throws Exception {
        int code = new ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "2", "-nodes",
                "-keyout", key.toString(),
                "-out", crt.toString(),
                "-subj", "/CN=localhost",
                "-addext", "subjectAltName=" + san,
                "-addext", "extendedKeyUsage=serverAuth",
                "-addext", "keyUsage=digitalSignature,keyEncipherment")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        assertEquals(0, code);
    }

    private static void readable(Path secrets) throws IOException {
        Set<PosixFilePermission> open = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        try (var entries = Files.list(secrets)) {
            for (Path entry : entries.toList()) {
                Files.setPosixFilePermissions(entry, open);
            }
        }
        Files.setPosixFilePermissions(secrets, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
    }

    private static void assertPortFree(int port) throws IOException {
        try (var socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        } catch (IOException ex) {
            fail("port " + port + " is in use");
        }
    }

    private static String secret() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static List<String> activeLines(Path conf) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String raw : Files.readAllLines(conf)) {
            String stripped = raw.split("#", 2)[0].strip();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static int indexOfPrefix(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The broker process is already stopped. A leftover temp file is not a house secret.
                }
            });
        }
    }

    private static CommandResult run(List<String> command, byte[] stdin) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        String prefix = System.getenv("MOSQUITTO_PREFIX");
        if (prefix != null && !prefix.isBlank()) {
            builder.environment().put("LD_LIBRARY_PATH", Path.of(prefix, "lib").toString());
        }
        Process process = builder.start();
        if (stdin != null) {
            try (OutputStream out = process.getOutputStream()) {
                out.write(stdin);
            }
        } else {
            process.getOutputStream().close();
        }
        byte[] out = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("command timed out");
        }
        return new CommandResult(new String(out, StandardCharsets.UTF_8), "", process.exitValue());
    }

    private record CommandResult(String stdout, String stderr, int code) {
    }

    private enum BrokerKind {
        DOCKER,
        LOCAL,
        ABSENT,
        REQUIRED;

        Path mosquitto;
        Path ctrl;
        Path plugin;
        Path lib;

        static BrokerKind detect() {
            if (commandSucceeds("docker", "info")) {
                return DOCKER;
            }
            String prefix = System.getenv("MOSQUITTO_PREFIX");
            if (prefix != null && !prefix.isBlank()) {
                BrokerKind local = LOCAL;
                local.mosquitto = Path.of(prefix, "sbin", "mosquitto");
                local.ctrl = Path.of(prefix, "bin", "mosquitto_ctrl");
                local.plugin = Path.of(prefix, "lib", "mosquitto_dynamic_security.so");
                local.lib = Path.of(prefix, "lib");
                if (Files.isExecutable(local.mosquitto)
                        && Files.isExecutable(local.ctrl)
                        && Files.isRegularFile(local.plugin)) {
                    return local;
                }
            }
            if ("true".equals(System.getenv("CI"))) {
                return REQUIRED;
            }
            return ABSENT;
        }
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static final class Broker implements AutoCloseable {
        private final Process process;
        private final String container;
        private final Path log;

        private Broker(Process process, String container, Path log) {
            this.process = process;
            this.container = container;
            this.log = log;
        }

        static Broker start(BrokerKind kind, Path work, Path secrets, Path data, boolean expectJson) throws Exception {
            if (kind == BrokerKind.DOCKER) {
                String name = "fd319-" + UUID.randomUUID().toString().substring(0, 8);
                ProcessBuilder builder = new ProcessBuilder(
                        "docker", "run", "-d", "--name", name,
                        "-p", "127.0.0.1:8883:8883",
                        "-p", "127.0.0.1:8884:8884",
                        "-v", Path.of("..", "..", "mosquitto", "mosquitto.conf").toAbsolutePath()
                                + ":/mosquitto/config/mosquitto.conf:ro",
                        "-v", secrets.toAbsolutePath() + ":/mosquitto/config/secrets:ro",
                        "-v", data.toAbsolutePath() + ":/mosquitto/data",
                        IMAGE);
                builder.redirectErrorStream(true);
                Process started = builder.start();
                String id = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (!started.waitFor(60, TimeUnit.SECONDS) || started.exitValue() != 0 || id.isBlank()) {
                    fail("docker did not start mosquitto");
                }
                return new Broker(null, name, null);
            }
            Path conf = work.resolve("mosquitto.conf");
            Files.writeString(conf, localConf(kind, work, secrets, data));
            Path log = work.resolve(expectJson ? "broker-warm.log" : "broker-cold.log");
            ProcessBuilder builder = new ProcessBuilder(kind.mosquitto.toString(), "-c", conf.toString());
            builder.environment().put("LD_LIBRARY_PATH", kind.lib.toString());
            builder.environment().remove("MOSQUITTO_DYNSEC_PASSWORD");
            builder.redirectOutput(log.toFile());
            builder.redirectErrorStream(true);
            return new Broker(builder.start(), null, log);
        }

        void awaitListening() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                if (process != null && !process.isAlive() && process.exitValue() != 0) {
                    fail("mosquitto exited before opening listeners\n" + tail(logs()));
                }
                String text = logs();
                if (text.contains("listen socket on port 8883") && text.contains("listen socket on port 8884")) {
                    return;
                }
                Thread.sleep(200);
            }
            fail("mosquitto did not open 8883 and 8884\n" + tail(logs()));
        }

        private static String tail(String text) {
            if (text.length() <= 2000) {
                return text;
            }
            return text.substring(text.length() - 2000);
        }

        String logs() throws Exception {
            if (container != null) {
                ProcessBuilder logs = new ProcessBuilder("docker", "logs", container);
                logs.redirectErrorStream(true);
                Process process = logs.start();
                byte[] out = process.getInputStream().readAllBytes();
                process.waitFor(10, TimeUnit.SECONDS);
                return new String(out, StandardCharsets.UTF_8);
            }
            return Files.exists(log) ? Files.readString(log) : "";
        }

        @Override
        public void close() {
            if (container != null) {
                try {
                    new ProcessBuilder("docker", "rm", "-f", container)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor(20, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // The temp container name is unique. A failed rm does not keep the broker password.
                }
            }
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }

        private static String localConf(BrokerKind kind, Path work, Path secrets, Path data) throws IOException {
            String text = Files.readString(Path.of("..", "..", "mosquitto", "mosquitto.conf"));
            text = text.replace(
                    "plugin /usr/lib/mosquitto_dynamic_security.so",
                    "plugin " + kind.plugin.toAbsolutePath());
            text = text.replace(
                    "persistence_location /mosquitto/data/",
                    "persistence_location " + data.toAbsolutePath() + "/");
            text = text.replace(
                    "plugin_opt_config_file /mosquitto/data/dynamic-security.json",
                    "plugin_opt_config_file " + data.resolve("dynamic-security.json").toAbsolutePath());
            text = text.replace(
                    "plugin_opt_password_init_file /mosquitto/config/secrets/dynsec-password-init",
                    "plugin_opt_password_init_file " + work.resolve("missing-dynsec-password-init").toAbsolutePath());
            text = text.replace("/mosquitto/config/secrets/", secrets.toAbsolutePath() + "/");
            text = text.replace("listener 8883\n", "listener 8883 127.0.0.1\n");
            text = text.replace("listener 8884\n", "listener 8884 127.0.0.1\n");
            return text;
        }
    }

    private static final class Session implements AutoCloseable {
        private final PahoMqttConnection connection;
        private final LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();

        private Session(PahoMqttConnection connection) {
            this.connection = connection;
        }

        static Session open(String username, String password, Path trust) {
            PahoMqttConnection connection = new PahoMqttConnection(
                    new MqttEndpoint("127.0.0.1", 8883, true, username), password, trust);
            connection.connect();
            return new Session(connection);
        }

        void listen(String topic) {
            connection.subscribe(topic, 1, (name, payload) ->
                    inbox.add(name + " " + new String(payload, StandardCharsets.UTF_8)));
        }

        void publish(String topic, String body) {
            connection.publish(topic, body.getBytes(StandardCharsets.UTF_8), 1, false);
        }

        String poll(Duration timeout) throws InterruptedException {
            return inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            connection.close();
        }
    }
}
