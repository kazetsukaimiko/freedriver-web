package io.freedriver.app.phones;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.freedriver.app.invites.InviteUpstreamException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakPhoneSignInTest {

    @Test
    void removal_uses_the_invite_client_and_the_phone_sign_in_group() throws Exception {
        AtomicReference<String> tokenBody = new AtomicReference<>();
        AtomicReference<String> deletePath = new AtomicReference<>();
        AtomicReference<String> userQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/freedriver/protocol/openid-connect/token", exchange -> {
            tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"tok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/admin/realms/freedriver/groups", exchange -> {
            byte[] body = "[{\"id\":\"group-1\",\"name\":\"phone-sign-in\",\"parentId\":null}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/admin/realms/freedriver/users", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/groups/")) {
                deletePath.set(path);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            userQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = userBody("+15555550123").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String auth = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/freedriver";
            KeycloakPhoneSignIn client = new KeycloakPhoneSignIn(
                    new ObjectMapper(), auth, "freedriver-invite", "secret-value");
            assertTrue(client.removeFromPhoneSignIn("+15555550123"));
            assertTrue(tokenBody.get().contains("client_id=freedriver-invite"));
            assertTrue(tokenBody.get().contains("client_secret=secret-value"));
            assertEquals("/admin/realms/freedriver/users/user-1/groups/group-1", deletePath.get());
            assertTrue(userQuery.get().contains("exact=true"));
            assertTrue(userQuery.get().contains("briefRepresentation=false"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_group_with_a_parent_is_not_removed() throws Exception {
        AtomicReference<String> deletePath = new AtomicReference<>();
        HttpServer server = server(deletePath,
                "[{\"id\":\"child\",\"name\":\"phone-sign-in\",\"parentId\":\"parent-1\"},"
                        + "{\"id\":\"other\",\"name\":\"phone-sign-in\",\"parent\":{\"id\":\"p\"}}]",
                userBody("+15555550123"));
        server.start();
        try {
            KeycloakPhoneSignIn client = client(server);
            InviteUpstreamException error = assertThrows(InviteUpstreamException.class,
                    () -> client.removeFromPhoneSignIn("+15555550123"));
            assertEquals("phone-sign-in group was not found", error.getMessage());
            assertNull(deletePath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nested_group_is_ignored_and_phone_must_match_before_delete() throws Exception {
        AtomicReference<String> deletePath = new AtomicReference<>();
        HttpServer server = server(deletePath,
                "[{\"id\":\"child\",\"name\":\"phone-sign-in\",\"parentId\":\"parent-1\"},"
                        + "{\"id\":\"group-1\",\"name\":\"phone-sign-in\"}]",
                userBody("+15555550123"));
        server.start();
        try {
            KeycloakPhoneSignIn client = client(server);
            assertTrue(client.removeFromPhoneSignIn("+15555550123"));
            assertEquals("/admin/realms/freedriver/users/user-1/groups/group-1", deletePath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mismatched_phone_attribute_does_not_delete() throws Exception {
        AtomicReference<String> deletePath = new AtomicReference<>();
        HttpServer server = server(deletePath,
                "[{\"id\":\"group-1\",\"name\":\"phone-sign-in\"}]",
                userBody("+15555550999"));
        server.start();
        try {
            KeycloakPhoneSignIn client = client(server);
            InviteUpstreamException error = assertThrows(InviteUpstreamException.class,
                    () -> client.removeFromPhoneSignIn("+15555550123"));
            assertEquals("phone attribute does not match", error.getMessage());
            assertNull(deletePath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void prod_refuses_a_blank_invite_client_secret() {
        assertThrows(IllegalStateException.class, () -> KeycloakPhoneSignIn.requireSecret(true, ""));
        assertThrows(IllegalStateException.class, () -> KeycloakPhoneSignIn.requireSecret(true, " "));
        KeycloakPhoneSignIn.requireSecret(false, "");
        KeycloakPhoneSignIn.requireSecret(true, "secret-value");
    }

    private static KeycloakPhoneSignIn client(HttpServer server) {
        String auth = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/freedriver";
        return new KeycloakPhoneSignIn(new ObjectMapper(), auth, "freedriver-invite", "secret-value");
    }

    private static String userBody(String phone) {
        return "[{\"id\":\"user-1\",\"attributes\":{\"phone\":[\"" + phone + "\"]}}]";
    }

    private static HttpServer server(AtomicReference<String> deletePath, String groups, String users) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/freedriver/protocol/openid-connect/token", exchange -> {
            byte[] body = "{\"access_token\":\"tok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/admin/realms/freedriver/groups", exchange -> {
            byte[] body = groups.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/admin/realms/freedriver/users", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/groups/")) {
                deletePath.set(path);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            byte[] body = users.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}
