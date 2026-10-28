package io.freedriver.app.instances;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.freedriver.mqtt.MqttLog;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
            assertTrue(posts.contains("\"mqtt_publ_topic\":[\"" + ApplianceSchemas.appliancesTopic(first) + "\"]"));
            assertTrue(posts.contains("\"mqtt_subs_topic\":[\"" + ApplianceSchemas.commandsTopic(first) + "\"]"));
            assertTrue(posts.contains("\"mqtt_publ_topic\":[\"" + ApplianceSchemas.appliancesTopic(second) + "\"]"));
            assertFalse(posts.contains("\"groups\""));
            assertFalse(realm.bodyOf("POST", second.toString()).contains(first.toString()));
            assertTrue(realm.calls.stream().anyMatch(call -> call.startsWith(
                    "PUT /admin/realms/freedriver/users/kc-" + first + "/groups/group-1")));
            assertFalse(realm.calls.stream().anyMatch(call -> call.startsWith("PUT /admin/realms/freedriver/groups")));
            assertTrue(realm.calls.stream().anyMatch(call -> call.contains("client_id=freedriver-instances")));
            assertTrue(realm.calls.stream().anyMatch(call -> call.contains("client_secret=secret-value")));
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
    void another_houses_topic_is_not_sent() {
        Realm realm = new Realm();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        KeycloakUser crossed = new KeycloakUser(
                second.toString(),
                second.toString(),
                null,
                "pw",
                ApplianceSchemas.appliancesTopic(first),
                ApplianceSchemas.commandsTopic(second),
                InstanceIdentity.GROUP);
        assertThrows(IllegalArgumentException.class, () -> new KeycloakInstanceIdentity(
                JSON, "http://127.0.0.1:9/realms/freedriver", "freedriver-instances", "secret-value").create(crossed));
        assertTrue(realm.calls.isEmpty());
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
                JSON, "http://127.0.0.1:9/realms/freedriver", "freedriver-instances", " ");
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
                ApplianceSchemas.appliancesTopic(id),
                ApplianceSchemas.commandsTopic(id),
                InstanceIdentity.GROUP);
    }

    private static final class Realm {
        private final Map<String, User> users = new LinkedHashMap<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private HttpServer server;
        private boolean failJoin;
        private boolean stripTopics;
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
            String auth = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/freedriver";
            return new KeycloakInstanceIdentity(JSON, auth, "freedriver-instances", "secret-value");
        }

        void seed(UUID id, String password) {
            User user = new User();
            user.id = "kc-" + id;
            user.username = id.toString();
            user.password = password;
            user.publish = ApplianceSchemas.appliancesTopic(id);
            user.subscribe = ApplianceSchemas.commandsTopic(id);
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
            calls.add(method + " " + path + (query.isEmpty() ? "" : "?" + query) + " " + text);
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
                user.publish = body.path("attributes").path("mqtt_publ_topic").path(0).asText();
                user.subscribe = body.path("attributes").path("mqtt_subs_topic").path(0).asText();
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
            String label = user.label == null ? "" : ",\"" + KeycloakInstanceIdentity.LABEL_ATTRIBUTE
                    + "\":[\"" + user.label + "\"]";
            String topics = stripTopics ? "" : ",\"" + KeycloakInstanceIdentity.PUBLISH_ATTRIBUTE + "\":[\""
                    + user.publish + "\"],\"" + KeycloakInstanceIdentity.SUBSCRIBE_ATTRIBUTE + "\":[\""
                    + user.subscribe + "\"]";
            return "\"id\":\"" + user.id + "\",\"username\":\"" + user.username + "\",\"attributes\":{"
                    + (label.isEmpty() ? "" : label.substring(1))
                    + (label.isEmpty() || topics.isEmpty() ? "" : "")
                    + (topics.isEmpty() ? "" : (label.isEmpty() ? topics.substring(1) : topics))
                    + "}";
        }

        private User userById(String id) {
            return users.values().stream().filter(user -> user.id.equals(id)).findFirst().orElseThrow();
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
        private String publish;
        private String subscribe;
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
