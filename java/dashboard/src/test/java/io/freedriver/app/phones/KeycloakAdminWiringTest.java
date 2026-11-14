package io.freedriver.app.phones;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.freedriver.app.instances.InstanceSummary;
import io.freedriver.app.instances.KeycloakInstanceIdentity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production wiring of both Keycloak admin clients against a stub realm: the injected default
 * {@code Keycloak} built from {@code quarkus.keycloak.admin-client.*} behind {@link KeycloakInstanceIdentity},
 * and {@link InviteAdminProducer} plus the {@code invite} OIDC client behind {@link KeycloakPhoneSignIn}.
 */
@QuarkusTest
@QuarkusTestResource(value = KeycloakAdminWiringTest.Realm.class, restrictToAnnotatedClass = true)
class KeycloakAdminWiringTest {

    static final UUID HOUSE = UUID.fromString("7d3c2a1e-0000-4000-8000-000000000001");

    @Inject
    KeycloakInstanceIdentity instances;

    @Inject
    KeycloakPhoneSignIn phones;

    @Test
    void the_injected_instances_client_lists_the_houses_as_freedriver_instances() {
        List<InstanceSummary> listed = instances.list();

        assertEquals(List.of(new InstanceSummary(HOUSE.toString(), "porch")), listed);
        assertTrue(Realm.tokenClients.contains("freedriver-instances:instances-secret"), Realm.tokenClients::toString);
        assertTrue(Realm.calls.contains("GET /admin/realms/freedriver/groups/group-m/members"), Realm.calls::toString);
    }

    @Test
    void the_produced_invite_client_removes_the_phone_as_freedriver_invite() {
        assertTrue(phones.removeFromPhoneSignIn("+15555550123"));

        assertTrue(Realm.tokenClients.contains("freedriver-invite:invite-secret"), Realm.tokenClients::toString);
        assertTrue(Realm.calls.contains("DELETE /admin/realms/freedriver/users/user-1/groups/group-p"),
                Realm.calls::toString);
    }

    /** One stub realm with the instances and the invite answers. */
    public static final class Realm implements QuarkusTestResourceLifecycleManager {

        static final List<String> tokenClients = new CopyOnWriteArrayList<>();
        static final List<String> calls = new CopyOnWriteArrayList<>();
        private HttpServer server;

        @Override
        public Map<String, String> start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", this::handle);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> config = new LinkedHashMap<>();
            config.put("freedriver.keycloak.server-url", base);
            config.put("quarkus.keycloak.admin-client.client-secret", "instances-secret");
            config.put("freedriver.instances.client-secret", "instances-secret");
            config.put("freedriver.invite.client-secret", "invite-secret");
            return config;
        }

        @Override
        public void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            calls.add(method + " " + path);
            if (path.endsWith("/protocol/openid-connect/token")) {
                String header = exchange.getRequestHeaders().getFirst("Authorization");
                if (header != null && header.startsWith("Basic ")) {
                    tokenClients.add(new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                            StandardCharsets.UTF_8));
                }
                send(exchange, 200, "{\"access_token\":\"tok\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
            } else if (path.equals("/admin/realms/freedriver/groups") && query.contains("search=mqtt-instance")) {
                send(exchange, 200, "[{\"id\":\"group-m\",\"name\":\"mqtt-instance\"}]");
            } else if (path.equals("/admin/realms/freedriver/groups") && query.contains("search=phone-sign-in")) {
                send(exchange, 200, "[{\"id\":\"group-p\",\"name\":\"phone-sign-in\"}]");
            } else if (path.equals("/admin/realms/freedriver/groups/group-m/members")) {
                send(exchange, 200, "[{\"id\":\"kc-1\",\"username\":\"" + HOUSE + "\","
                        + "\"attributes\":{\"label\":[\"porch\"]}}]");
            } else if (path.equals("/admin/realms/freedriver/users") && query.contains("q=phone:")) {
                send(exchange, 200, "[{\"id\":\"user-1\",\"attributes\":{\"phone\":[\"+15555550123\"]}}]");
            } else if ("DELETE".equals(method) && path.equals("/admin/realms/freedriver/users/user-1/groups/group-p")) {
                send(exchange, 204, "");
            } else {
                send(exchange, 500, "{}");
            }
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
