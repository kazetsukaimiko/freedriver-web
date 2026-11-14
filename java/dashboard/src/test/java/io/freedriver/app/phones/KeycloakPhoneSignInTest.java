package io.freedriver.app.phones;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.freedriver.app.invites.InviteUpstreamException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invite service account's Keycloak calls against a stub realm. The admin client and the
 * {@code invite} OIDC client fetch their own tokens; each test sets the realm's routes.
 */
@QuarkusTest
@QuarkusTestResource(value = KeycloakPhoneSignInTest.Realm.class, restrictToAnnotatedClass = true)
class KeycloakPhoneSignInTest {

    @RestClient
    InviteAccountsApi accounts;

    @BeforeEach
    void clear() {
        Realm.routes.clear();
    }

    @Test
    void removal_uses_the_invite_client_and_the_phone_sign_in_group() {
        AtomicReference<String> tokenCredentials = new AtomicReference<>();
        AtomicReference<String> deletePath = new AtomicReference<>();
        AtomicReference<String> userQuery = new AtomicReference<>();
        Realm.routes.put("/realms/freedriver/protocol/openid-connect/token", exchange -> {
            tokenCredentials.set(basic(exchange));
            Realm.send(exchange, 200, "{\"access_token\":\"tok\"}");
        });
        Realm.routes.put("/admin/realms/freedriver/groups", exchange -> Realm.send(exchange, 200,
                "[{\"id\":\"group-1\",\"name\":\"phone-sign-in\",\"parentId\":null}]"));
        Realm.routes.put("/admin/realms/freedriver/users", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/groups/")) {
                deletePath.set(path);
                Realm.send(exchange, 204, "");
                return;
            }
            userQuery.set(exchange.getRequestURI().getRawQuery());
            Realm.send(exchange, 200, userBody("+15555550123"));
        });

        assertTrue(client().removeFromPhoneSignIn("+15555550123"));
        assertEquals("freedriver-invite:secret-value", tokenCredentials.get());
        assertEquals("/admin/realms/freedriver/users/user-1/groups/group-1", deletePath.get());
        assertTrue(userQuery.get().contains("exact=true"));
        assertTrue(userQuery.get().contains("briefRepresentation=false"));
    }

    @Test
    void a_group_with_a_parent_is_not_removed() {
        AtomicReference<String> deletePath = new AtomicReference<>();
        realm(deletePath,
                "[{\"id\":\"child\",\"name\":\"phone-sign-in\",\"parentId\":\"parent-1\"},"
                        + "{\"id\":\"other\",\"name\":\"phone-sign-in\",\"parentId\":\"p\"}]",
                userBody("+15555550123"));

        InviteUpstreamException error = assertThrows(InviteUpstreamException.class,
                () -> client().removeFromPhoneSignIn("+15555550123"));
        assertEquals("phone-sign-in group was not found", error.getMessage());
        assertNull(deletePath.get());
    }

    @Test
    void nested_group_is_ignored_and_phone_must_match_before_delete() {
        AtomicReference<String> deletePath = new AtomicReference<>();
        realm(deletePath,
                "[{\"id\":\"child\",\"name\":\"phone-sign-in\",\"parentId\":\"parent-1\"},"
                        + "{\"id\":\"group-1\",\"name\":\"phone-sign-in\"}]",
                userBody("+15555550123"));

        assertTrue(client().removeFromPhoneSignIn("+15555550123"));
        assertEquals("/admin/realms/freedriver/users/user-1/groups/group-1", deletePath.get());
    }

    @Test
    void mismatched_phone_attribute_does_not_delete() {
        AtomicReference<String> deletePath = new AtomicReference<>();
        realm(deletePath, "[{\"id\":\"group-1\",\"name\":\"phone-sign-in\"}]", userBody("+15555550999"));

        InviteUpstreamException error = assertThrows(InviteUpstreamException.class,
                () -> client().removeFromPhoneSignIn("+15555550123"));
        assertEquals("phone attribute does not match", error.getMessage());
        assertNull(deletePath.get());
    }

    @Test
    void create_sends_only_the_phone_and_refuses_an_admin_role() {
        AtomicReference<String> body = new AtomicReference<>();
        Realm.routes.put("/realms/freedriver/protocol/openid-connect/token",
                exchange -> Realm.send(exchange, 200, "{\"access_token\":\"tok\"}"));
        Realm.routes.put("/realms/freedriver/freedriver-invite/users", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Realm.send(exchange, 200, "{\"username\":\"inv" + "ab".repeat(16)
                    + "\",\"groups\":[\"phone-sign-in\"],\"adminRoles\":[]}");
        });

        PhoneSignInDirectory.Created created = client().createPhoneSignInUser("+15555550123");
        assertEquals(List.of("phone-sign-in"), created.groups());
        assertTrue(created.adminRoles().isEmpty());
        assertTrue(body.get().contains("\"phone\":\"+15555550123\""));
        assertFalse(body.get().contains("portal-admin"));
        assertFalse(body.get().contains("credentials"));
        assertFalse(body.get().contains("realmRoles"));
        assertFalse(body.get().contains("groups"));
    }

    @Test
    void a_second_user_is_a_conflict_and_the_body_is_not_kept() {
        Realm.routes.put("/realms/freedriver/protocol/openid-connect/token",
                exchange -> Realm.send(exchange, 200, "{\"access_token\":\"tok\"}"));
        Realm.routes.put("/realms/freedriver/freedriver-invite/users", exchange -> Realm.send(exchange, 409, "{}"));

        PhoneAlreadyExists error = assertThrows(PhoneAlreadyExists.class,
                () -> client().createPhoneSignInUser("+15555550123"));
        assertFalse(error.getMessage().contains("555"));
    }

    @Test
    void blank_secret_does_not_call_keycloak() {
        KeycloakPhoneSignIn client = new KeycloakPhoneSignIn(
                () -> {
                    throw new AssertionError("admin client was built");
                },
                () -> {
                    throw new AssertionError("invite-account client was used");
                },
                "freedriver",
                false);
        InviteUpstreamException error = assertThrows(InviteUpstreamException.class,
                () -> client.removeFromPhoneSignIn("+15555550123"));
        assertEquals("invite client secret is unset", error.getMessage());
    }

    private KeycloakPhoneSignIn client() {
        Keycloak admin = KeycloakBuilder.builder()
                .serverUrl(Realm.base())
                .realm("freedriver")
                .clientId("freedriver-invite")
                .clientSecret("secret-value")
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
        return new KeycloakPhoneSignIn(() -> admin, () -> accounts, "freedriver", true);
    }

    private static String userBody(String phone) {
        return "[{\"id\":\"user-1\",\"attributes\":{\"phone\":[\"" + phone + "\"]}}]";
    }

    /** The client id and secret the token request presented as HTTP Basic. */
    private static String basic(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            return null;
        }
        return new String(Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
    }

    private static void realm(AtomicReference<String> deletePath, String groups, String users) {
        Realm.routes.put("/realms/freedriver/protocol/openid-connect/token",
                exchange -> Realm.send(exchange, 200, "{\"access_token\":\"tok\"}"));
        Realm.routes.put("/admin/realms/freedriver/groups", exchange -> Realm.send(exchange, 200, groups));
        Realm.routes.put("/admin/realms/freedriver/users", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/groups/")) {
                deletePath.set(path);
                Realm.send(exchange, 204, "");
                return;
            }
            Realm.send(exchange, 200, users);
        });
    }

    /** One stub Keycloak for the class. Each test puts its routes; the longest matching prefix answers. */
    public static final class Realm implements QuarkusTestResourceLifecycleManager {

        static final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();
        private static volatile int port;
        private HttpServer server;

        static String base() {
            return "http://127.0.0.1:" + port;
        }

        @Override
        public Map<String, String> start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                HttpHandler handler = routes.entrySet().stream()
                        .filter(route -> path.startsWith(route.getKey()))
                        .max(Comparator.comparingInt(route -> route.getKey().length()))
                        .map(Map.Entry::getValue)
                        .orElse(unrouted -> send(unrouted, 500, "{}"));
                handler.handle(exchange);
            });
            server.start();
            port = server.getAddress().getPort();
            Map<String, String> config = new LinkedHashMap<>();
            config.put("quarkus.rest-client.keycloak-invite.url", base());
            config.put("quarkus.oidc-client.invite.auth-server-url", base() + "/realms/freedriver");
            config.put("freedriver.invite.client-secret", "secret-value");
            return config;
        }

        @Override
        public void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        static void send(HttpExchange exchange, int status, String body) throws IOException {
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
