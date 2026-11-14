package io.freedriver.autonomymcp;

import io.freedriver.mcp.McpEndpoint;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With OIDC on, the dashboard receives the connecting user's own bearer exchanged at Keycloak:
 * the subject token is exactly the caller's access token, and nothing else reaches the dashboard.
 */
@QuarkusTest
@TestProfile(McpBearerTest.OidcOn.class)
@QuarkusTestResource(value = McpOidc.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = DashboardStub.class, restrictToAnnotatedClass = true)
class TokenExchangeTest {

    @Inject
    RedisDataSource redis;

    @BeforeEach
    void reset() {
        redis.flushall();
        DashboardStub.recorded.clear();
        DashboardStub.exchanges.clear();
        DashboardStub.down.set(false);
        DashboardStub.refuseExchange.set(false);
    }

    @Test
    void the_dashboard_receives_the_callers_exchanged_token() {
        String token = McpBearerTest.token("mcp-sub-exchange", Set.of("mcp-user"), McpResourceTest.RESOURCE,
                "9d0238fd-47cb-4e86-bf03-ac4c16f6107c", null, null);
        String session = initialize(token);
        call(token, session, "home_status")
                .body("result.isError", is(false));

        assertEquals(1, DashboardStub.exchanges.size(), DashboardStub.exchanges::toString);
        assertEquals(List.of(token), DashboardStub.exchanges.getFirst().get("subject_token"));
        assertEquals(List.of("freedriver-api"), DashboardStub.exchanges.getFirst().get("audience"));
        assertEquals(1, DashboardStub.recorded.size(), DashboardStub.recorded::toString);
        assertEquals("Bearer " + DashboardStub.exchanged(token), DashboardStub.recorded.getFirst().authorization());
    }

    @Test
    void a_refused_exchange_reaches_no_dashboard_route() {
        DashboardStub.refuseExchange.set(true);
        String token = McpBearerTest.token("mcp-sub-refused", Set.of("mcp-user"), McpResourceTest.RESOURCE,
                McpBearerTest.CLIENT_ID, null, null);
        String session = initialize(token);
        call(token, session, "home_status")
                .body("result.isError", is(true))
                .body("result.content[0].text", is("That was refused."));

        assertEquals(1, DashboardStub.exchanges.size(), DashboardStub.exchanges::toString);
        assertTrue(DashboardStub.recorded.isEmpty(), DashboardStub.recorded::toString);
    }

    private static String initialize(String token) {
        return given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + McpResourceTest.INIT_PARAMS + "}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .extract()
                .header(McpEndpoint.SESSION_HEADER);
    }

    private static io.restassured.response.ValidatableResponse call(String token, String session, String tool) {
        return given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200);
    }
}
