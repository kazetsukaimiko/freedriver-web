package io.freedriver.app.phones;

import com.sun.net.httpserver.HttpServer;
import io.freedriver.sms.model.InviteSend;
import io.freedriver.sms.model.PhoneList;
import io.freedriver.sms.model.RemovePhone;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two sms REST clients against a local stub: path, secret header, JSON, and status mapping. */
@QuarkusTest
@QuarkusTestResource(value = SmsClientWireTest.SmsStub.class, restrictToAnnotatedClass = true)
class SmsClientWireTest {

    @RestClient
    SmsPhones phones;

    @RestClient
    SmsInvite invites;

    @BeforeEach
    void clear() {
        SmsStub.calls.clear();
    }

    @Test
    void phone_calls_carry_the_secret_and_read_the_typed_bodies() {
        PhoneList list = phones.list();
        assertEquals(1, list.phones().size());
        assertEquals("+15555550123", list.phones().getFirst().phone());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), list.phones().getFirst().addedAt());

        assertTrue(phones.remove(new RemovePhone("+15555550123")).removed());

        assertEquals(List.of(
                "GET /sms/phones wire-secret ",
                "POST /sms/phones/remove wire-secret {\"phone\":\"+15555550123\"}"), SmsStub.calls);
    }

    @Test
    void an_error_status_becomes_sms_refused() {
        SmsRefused refused = assertThrows(SmsRefused.class,
                () -> invites.send(new InviteSend("+15555550199", "wording", "id")));
        assertEquals(409, refused.status());
    }

    @Test
    void a_code_the_client_refuses_is_a_wrong_code_and_never_reaches_sms() {
        HttpSmsPhoneList sms = new HttpSmsPhoneList(phones, invites, "wire-secret");

        assertEquals(SmsPhoneList.InviteCheck.WRONG, sms.checkInviteCode("+15555550123", "1".repeat(17)));
        assertEquals(SmsPhoneList.InviteCheck.WRONG, sms.checkInviteCode("+15555550123", null));
        assertTrue(SmsStub.calls.stream().noneMatch(call -> call.contains("/sms/invite/verify")), SmsStub.calls::toString);
    }

    public static final class SmsStub implements QuarkusTestResourceLifecycleManager {

        static final List<String> calls = new CopyOnWriteArrayList<>();
        private HttpServer server;

        @Override
        public Map<String, String> start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/sms", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getPath();
                calls.add(exchange.getRequestMethod() + " " + path + " "
                        + exchange.getRequestHeaders().getFirst(SmsSecretHeaders.HEADER) + " " + body);
                int status = 200;
                String answer = "{}";
                if ("/sms/phones".equals(path)) {
                    answer = "{\"phones\":[{\"phone\":\"+15555550123\",\"name\":\"Ada\",\"role\":\"dashboard\","
                            + "\"addedAt\":\"2026-11-01T00:00:00Z\",\"consentedAt\":null,\"lastSignIn\":null}]}";
                } else if ("/sms/phones/remove".equals(path)) {
                    answer = "{\"removed\":true}";
                } else if ("/sms/invite/send".equals(path)) {
                    status = 409;
                    answer = "{\"error\":\"already-listed\"}";
                }
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return Map.of(
                    "quarkus.rest-client.sms.url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sms.otp.shared-secret", "wire-secret");
        }

        @Override
        public void stop() {
            if (server != null) {
                server.stop(0);
            }
        }
    }
}
