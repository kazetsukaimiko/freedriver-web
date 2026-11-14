package io.freedriver.app.jobs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /api/jobs reads the dispatcher's admin job list as freedriver-portal-jobs and returns typed rows. */
@QuarkusTest
@QuarkusTestResource(value = AgentJobsResourceTest.Dispatcher.class, restrictToAnnotatedClass = true)
class AgentJobsResourceTest {

    @BeforeEach
    void up() {
        Dispatcher.down.set(false);
    }

    @Test
    @TestSecurity(user = "jobs-admin", roles = "portal-admin")
    void every_page_of_the_dispatchers_assignments_is_listed_as_typed_rows() {
        given().when()
                .get("/api/jobs")
                .then()
                .statusCode(200)
                .body("jobs", hasSize(2))
                .body("jobs[0].assignmentId", equalTo("11111111-1111-1111-1111-111111111111"))
                .body("jobs[0].prompt", equalTo("say hello"))
                .body("jobs[0].channel", equalTo("TEXT"))
                .body("jobs[0].state", equalTo("QUEUED"))
                .body("jobs[0].note", equalTo("not yet"))
                .body("jobs[1].prompt", equalTo("older"))
                .body("jobs[1].state", equalTo("SUCCEEDED"));

        assertTrue(Dispatcher.calls.contains("GET /api/dispatcher/admin/jobs?limit=200 Bearer tok"), Dispatcher.calls::toString);
        assertTrue(Dispatcher.calls.stream().anyMatch(call -> call.startsWith("GET /api/dispatcher/admin/jobs?limit=200&cursor=")),
                Dispatcher.calls::toString);
        assertTrue(Dispatcher.calls.contains("POST /realms/freedriver/protocol/openid-connect/token freedriver-portal-jobs:portal-secret"),
                Dispatcher.calls::toString);
    }

    @Test
    @TestSecurity(user = "jobs-admin", roles = "portal-admin")
    void a_refusing_dispatcher_is_502() {
        Dispatcher.down.set(true);
        given().when().get("/api/jobs").then().statusCode(502).body("error", equalTo("unavailable"));
    }

    /** A stub dispatcher and token endpoint. Two pages: the first names a cursor, the second does not. */
    public static final class Dispatcher implements QuarkusTestResourceLifecycleManager {

        static final List<String> calls = new CopyOnWriteArrayList<>();
        static final AtomicBoolean down = new AtomicBoolean();
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
            return Map.of(
                    "quarkus.rest-client.dispatcher.url", base,
                    "quarkus.oidc-client.portal-jobs.auth-server-url", base + "/realms/freedriver",
                    "quarkus.oidc-client.portal-jobs.credentials.secret", "portal-secret");
        }

        @Override
        public void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if (path.endsWith("/protocol/openid-connect/token")) {
                String header = exchange.getRequestHeaders().getFirst("Authorization");
                String basic = header != null && header.startsWith("Basic ")
                        ? new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8)
                        : "";
                calls.add("POST " + path + " " + basic);
                send(exchange, 200, "{\"access_token\":\"tok\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
                return;
            }
            calls.add("GET " + path + (query == null ? "" : "?" + query) + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            if (down.get()) {
                send(exchange, 403, "{}");
                return;
            }
            if (query != null && query.contains("cursor=")) {
                send(exchange, 200, "{\"assignments\":[" + row("22222222-2222-2222-2222-222222222222", "older",
                        "SUCCEEDED", "done") + "],\"next\":null}");
                return;
            }
            send(exchange, 200, "{\"assignments\":[" + row("11111111-1111-1111-1111-111111111111", "say hello",
                    "QUEUED", "not yet") + "],\"next\":\"1700000000000/11111111-1111-1111-1111-111111111111\"}");
        }

        private static String row(String id, String prompt, String state, String note) {
            return "{\"assignmentId\":\"" + id + "\",\"jobId\":\"33333333-3333-3333-3333-333333333333\","
                    + "\"deviceId\":\"pc-a\",\"agentName\":\"yuni\",\"channel\":\"TEXT\",\"prompt\":\"" + prompt + "\","
                    + "\"state\":\"" + state + "\",\"grokSessionId\":null,\"note\":\"" + note + "\",\"on_busy\":\"supersede\"}";
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
