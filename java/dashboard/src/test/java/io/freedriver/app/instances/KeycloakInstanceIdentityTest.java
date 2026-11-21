package io.freedriver.app.instances;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.freedriver.mqtt.MqttLog;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.KeycloakBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The instances admin client against a stub realm. The client fetches and reuses its own token. */
@QuarkusTest
class KeycloakInstanceIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void create_sets_that_houses_topics_and_joins_mqtt_instance() throws Exception {
        Realm realm = new Realm();
        realm.start();
        try (LogLines logs = LogLines.open()) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            KeycloakInstanceIdentity client = realm.client();
            client.create(user(first, "porch"));
            client.create(user(second, null));
            String posts = realm.posts();
            assertTrue(posts.contains("\"username\":\"" + first + "\""));
            assertTrue(posts.contains("\"label\":[\"porch\"]"));
            assertTrue(posts.contains(publishPair(first)));
            assertTrue(posts.contains(subscribePair(first)));
            assertTrue(posts.contains(publishPair(second)));
            assertTrue(posts.contains(subscribePair(second)));
            assertFalse(posts.contains("\"groups\""));
            assertFalse(realm.bodyOf("POST", second.toString()).contains(first.toString()));
            assertTrue(realm.calls.stream().anyMatch(call -> call.startsWith(
                    "PUT /admin/realms/freedriver/users/kc-" + first + "/groups/group-1")));
            assertFalse(realm.calls.stream().anyMatch(call -> call.startsWith("PUT /admin/realms/freedriver/groups")));
            assertTrue(realm.calls.stream().anyMatch(call -> call.contains("basic=freedriver-instances:secret-value")));
            assertTrue(logs.all().contains("mqtt house instance=" + first + " outcome=created"));
            assertTrue(logs.all().contains("mqtt house instance=" + second + " outcome=created"));
            assertFalse(logs.all().contains(realm.passwordOf(first)));
            assertEquals(2, realm.users.size());
        } finally {
            realm.stop();
        }
    }

    @Test
    void a_stop_short_deletes_the_user_it_created() throws Exception {
        Realm realm = new Realm();
        realm.failJoin = true;
        realm.start();
        try (LogLines logs = LogLines.open()) {
            UUID id = UUID.randomUUID();
            KeycloakUser user = user(id, "porch");
            assertThrows(BrokerRefusedException.class, () -> realm.client().create(user));
            assertTrue(realm.calls.stream().anyMatch(call -> call.startsWith(
                    "DELETE /admin/realms/freedriver/users/kc-" + id)));
            assertTrue(realm.users.isEmpty());
            assertTrue(logs.all().contains("mqtt house instance=" + id + " outcome=refused"));
            assertFalse(logs.all().contains(user.password()));
        } finally {
            realm.stop();
        }
    }

    @Test
    void missing_topic_attributes_delete_the_user() throws Exception {
        Realm realm = new Realm();
        realm.stripTopics = true;
        realm.start();
        try {
            UUID id = UUID.randomUUID();
            assertThrows(BrokerRefusedException.class, () -> realm.client().create(user(id, null)));
            assertTrue(realm.users.isEmpty());
            assertTrue(realm.calls.stream().anyMatch(call -> call.startsWith(
                    "DELETE /admin/realms/freedriver/users/kc-" + id)));
        } finally {
            realm.stop();
        }
    }

    @Test
    void a_username_that_is_not_the_house_never_calls_keycloak() {
        UUID id = UUID.randomUUID();
        KeycloakUser crossed = new KeycloakUser(
                id.toString(),
                "not-" + id,
                null,
                "pw",
                InstanceIdentity.GROUP);
        assertThrows(IllegalArgumentException.class, () -> new KeycloakInstanceIdentity(
                () -> {
                    throw new AssertionError("admin client was built");
                }, "freedriver", true).create(crossed));
    }

    @Test
    void reversed_topic_order_round_trips() throws Exception {
        Realm realm = new Realm();
        realm.readBack = ReadBack.REVERSED;
        realm.start();
        try {
            UUID id = UUID.randomUUID();
            realm.client().create(user(id, "porch"));
            assertEquals(1, realm.users.size());
            assertTrue(realm.posts().contains(publishPair(id)));
            assertTrue(realm.posts().contains(subscribePair(id)));
        } finally {
            realm.stop();
        }
    }

    @Test
    void a_truncated_topic_pair_deletes_the_user() throws Exception {
        refused(ReadBack.TRUNCATED);
    }

    @Test
    void an_extra_topic_deletes_the_user() throws Exception {
        refused(ReadBack.EXTRA);
    }

    @Test
    void a_duplicate_topic_deletes_the_user() throws Exception {
        refused(ReadBack.DUPLICATE_PAIR);
    }

    @Test
    void a_duplicate_beside_the_pair_deletes_the_user() throws Exception {
        refused(ReadBack.DUPLICATE_BESIDE);
    }

    @Test
    void another_houses_topics_delete_the_user() throws Exception {
        refused(ReadBack.FOREIGN);
    }

    @Test
    void reset_sends_the_new_password_and_does_not_read_the_previous_one() throws Exception {
        Realm realm = new Realm();
        realm.start();
        try (LogLines logs = LogLines.open()) {
            UUID id = UUID.randomUUID();
            realm.seed(id, "previous-password-value");
            String next = "next-password-value-not-the-previous";
            realm.client().reset(new ResetPassword(id.toString(), next));
            assertEquals(next, realm.users.get(id.toString()).password);
            assertFalse(String.join("\n", realm.calls).contains("previous-password-value"));
            assertFalse(realm.calls.stream().anyMatch(call -> call.contains("/credentials")));
            assertTrue(realm.calls.stream().anyMatch(call ->
                    call.startsWith("PUT /admin/realms/freedriver/users/kc-" + id + "/reset-password")
                            && call.contains(next)));
            assertTrue(logs.all().contains("mqtt house instance=" + id + " outcome=reset"));
            assertFalse(logs.all().contains(next));
            assertFalse(logs.all().contains("previous-password-value"));
        } finally {
            realm.stop();
        }
    }

    @Test
    void list_returns_mqtt_instance_users_without_passwords() throws Exception {
        Realm realm = new Realm();
        realm.start();
        try {
            UUID labeled = UUID.randomUUID();
            UUID blank = UUID.randomUUID();
            realm.seed(labeled, "member-secret");
            realm.users.get(labeled.toString()).label = "porch";
            realm.seed(blank, "other-secret");
            realm.stranger = "not-in-the-group";
            List<InstanceSummary> listed = realm.client().list();
            assertEquals(2, listed.size());
            assertTrue(listed.stream().anyMatch(row -> labeled.toString().equals(row.instanceId())
                    && "porch".equals(row.label())));
            assertTrue(listed.stream().anyMatch(row -> blank.toString().equals(row.instanceId())
                    && row.label() == null));
            assertFalse(listed.toString().contains("member-secret"));
            assertFalse(listed.toString().contains("other-secret"));
            assertFalse(listed.toString().contains("not-in-the-group"));
        } finally {
            realm.stop();
        }
    }

    @Test
    void a_blank_secret_does_not_call_keycloak() {
        KeycloakInstanceIdentity client = new KeycloakInstanceIdentity(
                () -> {
                    throw new AssertionError("admin client was built");
                }, "freedriver", false);
        UUID id = UUID.randomUUID();
        try (LogLines logs = LogLines.open()) {
            assertThrows(BrokerRefusedException.class, () -> client.create(user(id, null)));
            assertTrue(logs.all().contains("outcome=refused"));
            assertFalse(logs.all().contains("secret"));
        }
    }

    private static KeycloakUser user(UUID id, String label) {
        return new KeycloakUser(
                id.toString(),
                id.toString(),
                label,
                "pw-" + id.toString().substring(0, 8),
                InstanceIdentity.GROUP);
    }

    private static void refused(ReadBack readBack) throws Exception {
        Realm realm = new Realm();
        realm.readBack = readBack;
        realm.start();
        try {
            UUID id = UUID.randomUUID();
            assertThrows(BrokerRefusedException.class, () -> realm.client().create(user(id, null)));
            assertTrue(realm.users.isEmpty());
            assertTrue(realm.calls.stream().anyMatch(call -> call.startsWith(
                    "DELETE /admin/realms/freedriver/users/kc-" + id)));
        } finally {
            realm.stop();
        }
    }

    private static String publishPair(UUID id) {
        return "\"mqtt_publ_topic\":[\"" + ApplianceSchemas.appliancesTopic(id) + "\",\""
                + ApplianceSchemas.scheduledTopic(id) + "\"]";
    }

    private static String subscribePair(UUID id) {
        return "\"mqtt_subs_topic\":[\"" + ApplianceSchemas.commandsTopic(id) + "\",\""
                + ApplianceSchemas.commandListsTopic(id) + "\"]";
    }

    private enum ReadBack {
        AS_POSTED, REVERSED, TRUNCATED, EXTRA, DUPLICATE_PAIR, DUPLICATE_BESIDE, FOREIGN
    }

    private static final class Realm {
        private final Map<String, User> users = new LinkedHashMap<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private HttpServer server;
        private boolean failJoin;
        private boolean stripTopics;
        private ReadBack readBack = ReadBack.AS_POSTED;
        private final UUID foreign = UUID.randomUUID();
        private String stranger;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        KeycloakInstanceIdentity client() {
            var keycloak = KeycloakBuilder.builder()
                    .serverUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .realm("freedriver")
                    .clientId("freedriver-instances")
                    .clientSecret("secret-value")
                    .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                    .build();
            return new KeycloakInstanceIdentity(() -> keycloak, "freedriver", true);
        }

        void seed(UUID id, String password) {
            User user = new User();
            user.id = "kc-" + id;
            user.username = id.toString();
            user.password = password;
            user.publish = List.of(ApplianceSchemas.appliancesTopic(id), ApplianceSchemas.scheduledTopic(id));
            user.subscribe = List.of(ApplianceSchemas.commandsTopic(id), ApplianceSchemas.commandListsTopic(id));
            user.inGroup = true;
            users.put(user.username, user);
        }

        String posts() {
            return calls.stream().filter(call -> call.startsWith("POST /admin")).reduce("", (a, b) -> a + "\n" + b);
        }

        String bodyOf(String method, String contains) {
            return calls.stream()
                    .filter(call -> call.startsWith(method + " ") && call.contains(contains))
                    .findFirst()
                    .orElse("");
        }

        String passwordOf(UUID id) {
            return users.get(id.toString()).password;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
            String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + (query.isEmpty() ? "" : "?" + query) + " " + text + basic(exchange));
            if (path.endsWith("/protocol/openid-connect/token")) {
                send(exchange, 200, "{\"access_token\":\"tok\"}", null);
                return;
            }
            if ("GET".equals(method) && path.endsWith("/groups") && !path.contains("/users/")) {
                send(exchange, 200,
                        "[{\"id\":\"child\",\"name\":\"mqtt-instance\",\"parentId\":\"parent-1\"},"
                                + "{\"id\":\"group-1\",\"name\":\"mqtt-instance\"}]",
                        null);
                return;
            }
            if ("POST".equals(method) && path.endsWith("/users")) {
                JsonNode body = JSON.readTree(text);
                String username = body.path("username").asText();
                if (users.containsKey(username)) {
                    send(exchange, 409, "{}", null);
                    return;
                }
                User user = new User();
                user.id = "kc-" + username;
                user.username = username;
                user.password = body.path("credentials").path(0).path("value").asText();
                user.label = body.path("attributes").path("label").path(0).asText(null);
                user.publish = texts(body.path("attributes").path("mqtt_publ_topic"));
                user.subscribe = texts(body.path("attributes").path("mqtt_subs_topic"));
                users.put(username, user);
                send(exchange, 201, "", "http://127.0.0.1/admin/realms/freedriver/users/" + user.id);
                return;
            }
            if (path.contains("/reset-password")) {
                String id = path.substring(path.indexOf("/users/") + "/users/".length(), path.indexOf("/reset-password"));
                User user = userById(id);
                user.password = JSON.readTree(text).path("value").asText();
                send(exchange, 204, "", null);
                return;
            }
            if (path.contains("/groups/") && "PUT".equals(method)) {
                if (failJoin) {
                    send(exchange, 500, "{}", null);
                    return;
                }
                String id = path.substring(path.indexOf("/users/") + "/users/".length(), path.indexOf("/groups/"));
                userById(id).inGroup = true;
                send(exchange, 204, "", null);
                return;
            }
            if ("DELETE".equals(method) && path.contains("/users/")) {
                String id = path.substring(path.lastIndexOf('/') + 1);
                users.values().removeIf(user -> user.id.equals(id));
                send(exchange, 204, "", null);
                return;
            }
            if ("GET".equals(method) && path.contains("/members")) {
                List<String> rows = new ArrayList<>();
                for (User user : users.values()) {
                    if (user.inGroup) {
                        rows.add(member(user));
                    }
                }
                if (stranger != null) {
                    rows.add("{\"id\":\"stranger\",\"username\":\"" + stranger + "\","
                            + "\"credentials\":[{\"value\":\"stranger-secret\"}]}");
                }
                send(exchange, 200, "[" + String.join(",", rows) + "]", null);
                return;
            }
            if ("GET".equals(method) && path.endsWith("/groups")) {
                String id = path.substring(path.indexOf("/users/") + "/users/".length(), path.indexOf("/groups"));
                User user = userById(id);
                String body = user.inGroup ? "[{\"id\":\"group-1\",\"name\":\"mqtt-instance\"}]" : "[]";
                send(exchange, 200, body, null);
                return;
            }
            if ("GET".equals(method) && query.startsWith("username=")) {
                String username = query.substring("username=".length()).split("&", 2)[0];
                User user = users.get(username);
                String body = user == null ? "[]" : "[{" + identity(user) + "}]";
                send(exchange, 200, body, null);
                return;
            }
            if ("GET".equals(method) && path.contains("/users/")) {
                User user = userById(path.substring(path.lastIndexOf('/') + 1));
                send(exchange, 200, "{" + identity(user) + "}", null);
                return;
            }
            send(exchange, 500, "{}", null);
        }

        private String member(User user) {
            return "{" + identity(user) + ",\"credentials\":[{\"value\":\"" + user.password + "\"}]}";
        }

        private String identity(User user) {
            StringBuilder attributes = new StringBuilder();
            if (user.label != null) {
                attributes.append("\"").append(KeycloakInstanceIdentity.LABEL_ATTRIBUTE)
                        .append("\":[\"").append(user.label).append("\"]");
            }
            if (!stripTopics) {
                if (attributes.length() > 0) {
                    attributes.append(',');
                }
                attributes.append("\"").append(KeycloakInstanceIdentity.PUBLISH_ATTRIBUTE).append("\":")
                        .append(jsonArray(shown(user.publish, true)));
                attributes.append(",\"").append(KeycloakInstanceIdentity.SUBSCRIBE_ATTRIBUTE).append("\":")
                        .append(jsonArray(shown(user.subscribe, false)));
            }
            return "\"id\":\"" + user.id + "\",\"username\":\"" + user.username
                    + "\",\"attributes\":{" + attributes + "}";
        }

        private List<String> shown(List<String> stored, boolean publish) {
            List<String> values = new ArrayList<>(stored);
            return switch (readBack) {
                case AS_POSTED -> values;
                case REVERSED -> {
                    Collections.reverse(values);
                    yield values;
                }
                case TRUNCATED -> values.subList(0, Math.min(1, values.size()));
                case EXTRA -> {
                    values.add(ApplianceSchemas.appliancesTopic(foreign));
                    yield values;
                }
                case DUPLICATE_PAIR -> publish ? List.of(values.getFirst(), values.getFirst()) : values;
                case DUPLICATE_BESIDE -> {
                    if (publish) {
                        values.add(values.getFirst());
                    }
                    yield values;
                }
                case FOREIGN -> publish
                        ? List.of(ApplianceSchemas.appliancesTopic(foreign), ApplianceSchemas.scheduledTopic(foreign))
                        : List.of(ApplianceSchemas.commandsTopic(foreign), ApplianceSchemas.commandListsTopic(foreign));
            };
        }

        private static List<String> texts(JsonNode node) {
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> values.add(item.asText()));
            }
            return values;
        }

        private static String jsonArray(List<String> values) {
            StringBuilder array = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    array.append(',');
                }
                array.append('"').append(values.get(i)).append('"');
            }
            return array.append(']').toString();
        }

        private User userById(String id) {
            return users.values().stream().filter(user -> user.id.equals(id)).findFirst().orElseThrow();
        }

        /** The client credentials a request presented as HTTP Basic, as {@code " basic=id:secret"}. */
        private static String basic(HttpExchange exchange) {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.startsWith("Basic ")) {
                return "";
            }
            return " basic=" + new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                    StandardCharsets.UTF_8);
        }

        private static void send(HttpExchange exchange, int status, String body, String location) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (location != null) {
                exchange.getResponseHeaders().set("Location", location);
            }
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static final class User {
        private String id;
        private String username;
        private String password;
        private String label;
        private List<String> publish = List.of();
        private List<String> subscribe = List.of();
        private boolean inGroup;
    }

    private static final class LogLines extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(MqttLog.NAME);
        private final Level previous = logger.getLevel();
        private final List<String> lines = new ArrayList<>();

        private LogLines() {
            setLevel(Level.ALL);
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        static LogLines open() {
            return new LogLines();
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage() != null) {
                lines.add(record.getMessage());
            }
        }

        String all() {
            return String.join("\n", lines);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(previous);
        }
    }
}
