package io.freedriver.app.phones;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /api/phones/records and /api/sign-ins read sms, which owns those rows, and return typed rows. */
@QuarkusTest
@QuarkusTestResource(value = SmsRecordsResourceTest.Sms.class, restrictToAnnotatedClass = true)
class SmsRecordsResourceTest {

    @Test
    @TestSecurity(user = "records-admin", roles = "portal-admin")
    void phone_records_come_from_sms_as_typed_rows() {
        given().when()
                .get("/api/phones/records")
                .then()
                .statusCode(200)
                .body("phones", hasSize(1))
                .body("phones[0].id", equalTo("+15555550123"))
                .body("phones[0].phone", equalTo("+15555550123"))
                .body("phones[0].name", equalTo("Ada"))
                .body("phones[0].username", equalTo("ada"))
                .body("phones[0].role", equalTo("dashboard"))
                .body("phones[0].addedAt", equalTo("2026-11-01T10:00:00Z"));
        assertTrue(Sms.calls.contains("GET /sms/phones/records records-secret"), Sms.calls::toString);
    }

    @Test
    @TestSecurity(user = "records-admin", roles = "portal-admin")
    void sign_in_records_come_from_sms_as_typed_rows() {
        given().when()
                .get("/api/sign-ins")
                .then()
                .statusCode(200)
                .body("signIns", hasSize(1))
                .body("signIns[0].phone", equalTo("+15555550123"))
                .body("signIns[0].acceptedAt", equalTo("2026-11-02T09:00:00Z"));
        assertTrue(Sms.calls.contains("GET /sms/sign-ins records-secret"), Sms.calls::toString);
    }

    @Test
    @TestSecurity(user = "records-user", roles = "dashboard")
    void the_dashboard_role_is_refused() {
        given().when().get("/api/phones/records").then().statusCode(403);
        given().when().get("/api/sign-ins").then().statusCode(403);
    }

    /** A stub sms that answers the two record calls. */
    public static final class Sms implements QuarkusTestResourceLifecycleManager {

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
            return Map.of(
                    "quarkus.rest-client.sms.url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sms.otp.shared-secret", "records-secret");
        }

        @Override
        public void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path + " "
                    + exchange.getRequestHeaders().getFirst(SmsSecretHeaders.HEADER));
            String body = switch (path) {
                case "/sms/phones/records" -> "{\"phones\":[{\"id\":\"+15555550123\",\"phone\":\"+15555550123\","
                        + "\"name\":\"Ada\",\"username\":\"ada\",\"role\":\"dashboard\",\"addedAt\":\"2026-11-01T10:00:00Z\"}]}";
                case "/sms/sign-ins" -> "{\"signIns\":[{\"id\":\"+15555550123\",\"phone\":\"+15555550123\","
                        + "\"acceptedAt\":\"2026-11-02T09:00:00Z\"}]}";
                default -> null;
            };
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
