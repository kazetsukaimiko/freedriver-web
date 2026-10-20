package io.freedriver.app.phones;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakPhoneSignInTest {

    @Test
    void removal_uses_the_invite_client_and_the_phone_sign_in_group() throws Exception {
        AtomicReference<String> tokenBody = new AtomicReference<>();
        AtomicReference<String> deletePath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/freedriver/protocol/openid-connect/token", exchange -> {
            tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"tok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/admin/realms/freedriver/groups", exchange -> {
            byte[] body = "[{\"id\":\"group-1\",\"name\":\"phone-sign-in\"}]".getBytes(StandardCharsets.UTF_8);
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
            byte[] body = "[{\"id\":\"user-1\"}]".getBytes(StandardCharsets.UTF_8);
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
        } finally {
            server.stop(0);
        }
    }
}
