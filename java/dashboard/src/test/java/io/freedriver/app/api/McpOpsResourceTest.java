package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class McpOpsResourceTest {

    @Test
    void anonymous_mcp_ops_are_401() {
        given().when().get("/api/mcp/appliances").then().statusCode(401);
        given().when().get("/api/mcp/home-status").then().statusCode(401);
        given().when().get("/api/mcp/debug").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "portal-only", roles = "portal-admin")
    void portal_admin_without_mcp_user_is_403() {
        given().when().get("/api/mcp/appliances").then().statusCode(403);
        given().when().get("/api/mcp/home-status").then().statusCode(403);
        given().when().get("/api/mcp/debug").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void mcp_user_lists_appliances_and_home_status() {
        given().when().get("/api/mcp/appliances")
                .then()
                .statusCode(200)
                .body("instances.size()", is(1))
                .header("Set-Cookie", nullValue());
        given().when().get("/api/mcp/home-status")
                .then()
                .statusCode(200)
                .body("brokerConnected", is(false))
                .body("lastUpdated", notNullValue())
                .body("stale", is(false));
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void debug_without_mcp_debug_is_403() {
        given().when().get("/api/mcp/debug").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "mcp-debug-user", roles = {"mcp-user", "mcp-debug"})
    void debug_is_read_only_category() {
        given().when().get("/api/mcp/debug")
                .then()
                .statusCode(200)
                .body("health", is("UP"))
                .body("errorCategory", is("broker-down"));
    }
}
