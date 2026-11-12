package io.freedriver.autonomymcp;

import io.freedriver.autonomymcp.api.McpApi;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(value = DashboardStub.class, restrictToAnnotatedClass = true)
class McpResourceTest {

    @Inject
    ToolRateLimiter limiter;

    @Inject
    DashboardClient client;

    @Inject
    DelayedSwitchPump switches;

    @BeforeEach
    void reset() {
        limiter.reset();
        DashboardStub.recorded.clear();
        DashboardStub.down.set(false);
    }

    @Test
    void anonymous_mcp_is_401_without_login_redirect() {
        given().redirects().follow(false)
                .get("/autonomy/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE))
                .header("Location", nullValue());
        given().redirects().follow(false)
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE))
                .header("Location", nullValue());
        given().get("/appliances/mcp").then().statusCode(401);
        given().get("/mcp").then().statusCode(401);
        given().get("/dispatch/mcp").then().statusCode(401);
    }

    @Test
    void metadata_is_public_and_names_autonomy_mcp() {
        given().get("/api/autonomy/oauth-protected-resource")
                .then()
                .statusCode(200)
                .body("resource", is(McpProtectedResource.RESOURCE))
                .body("authorization_servers[0]", is(McpProtectedResource.AUTHORIZATION_SERVER));
    }

    @Test
    @TestSecurity(user = "portal-only", roles = "portal-admin")
    void portal_admin_without_mcp_user_is_403() {
        given().contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void list_appliances_does_not_copy_authorization() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_appliances\",\"arguments\":{}}}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("Fridge is on."))
                .header("Set-Cookie", nullValue());
        assertFalse(client.sentAuthorization());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "GET".equals(r.method()) && "/api/mcp/appliances".equals(r.path()) && r.authorization() == null));
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void switch_without_dashboard_role_is_refused() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body(switchBody("fridge", false))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(true))
                .body("result.content[0].text", is("That was refused."));
        assertTrue(DashboardStub.recorded.isEmpty());
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void switch_calls_dashboard_without_authorization() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body(switchBody("fridge", false))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Fridge turned off."));
        assertFalse(client.sentAuthorization());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "POST".equals(r.method())
                        && r.path().contains("/api/appliances/")
                        && r.authorization() == null
                        && "mcp-switch".equals(r.subject())));
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void zero_delay_still_waits_for_the_dashboard() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body(switchBody("fridge", false, 0))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Fridge turned off."));
        assertEquals(1, posts());
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void switch_schema_offers_optional_delay_seconds() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.type", is("integer"))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.minimum", is(0))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.maximum", is(86400))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.description", containsString("300"))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.required", hasItem("on"))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.required", not(hasItem("delaySeconds")));
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void delayed_switch_returns_before_the_house_and_fires_when_due() {
        DashboardStub.down.set(true);
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body(switchBody("fridge", false, 300))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Fridge will turn off in 5 minutes."));
        assertEquals(0, posts());
        switches.fireDue(Instant.now());
        assertEquals(0, posts());
        DashboardStub.down.set(false);
        switches.fireDue(Instant.now().plusSeconds(301));
        assertEquals(1, posts());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "POST".equals(r.method())
                        && r.path().endsWith("/fridge")
                        && "{\"on\":false}".equals(r.body())
                        && r.authorization() == null
                        && "mcp-switch".equals(r.subject())));
        switches.fireDue(Instant.now().plusSeconds(301));
        assertEquals(1, posts());
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void delay_rejects_a_duration_a_fraction_and_an_out_of_range_wait() {
        String session = initialize();
        for (String arguments : new String[] {
            baseSwitch() + ",\"duration\":300",
            baseSwitch() + ",\"delaySeconds\":-1",
            baseSwitch() + ",\"delaySeconds\":1.5",
            baseSwitch() + ",\"delaySeconds\":86401",
            baseSwitch() + ",\"delaySeconds\":\"300\""
        }) {
            given().contentType("application/json")
                    .header(McpApi.SESSION_HEADER, session)
                    .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"switch_appliance\",\"arguments\":{" + arguments + "}}}")
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("error.code", is(-32602))
                    .body("error.message", is("invalid params"));
        }
        assertEquals(0, posts());
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void home_status_is_spoken() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body(call("home_status"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", containsString("The broker is connected."))
                .body("result.content[0].text", containsString("Last state update"));
        assertFalse(client.sentAuthorization());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "GET".equals(r.method()) && "/api/mcp/home-status".equals(r.path()) && r.authorization() == null));
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void debug_without_mcp_debug_is_refused() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body(call("debug"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(true))
                .body("result.content[0].text", is("That was refused."));
        assertTrue(DashboardStub.recorded.isEmpty());
    }

    @Test
    @TestSecurity(user = "mcp-all", roles = {"mcp-user", "dashboard", "mcp-debug"})
    void dashboard_down_is_spoken_refusal_for_every_tool() {
        DashboardStub.down.set(true);
        String session = initialize();
        for (String body : new String[] {call("list_appliances"), call("home_status"), call("debug"), switchBody("fridge", false)}) {
            given().contentType("application/json")
                    .header(McpApi.SESSION_HEADER, session)
                    .body(body)
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(true))
                    .body("result.content[0].text", is("The house could not be reached."));
        }
    }

    @Test
    @TestSecurity(user = "mcp-debug-user", roles = {"mcp-user", "mcp-debug"})
    void debug_is_read_only_category() {
        String session = initialize();
        given().contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body(call("debug"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", containsString("Health UP"))
                .body("result.content[0].text", containsString("none"));
        assertFalse(client.sentAuthorization());
    }

    private static String initialize() {
        return given().contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .extract()
                .header(McpApi.SESSION_HEADER);
    }

    private static String call(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}";
    }

    private static String switchBody(String appliance, boolean on) {
        return switchBody(appliance, on, null);
    }

    private static String switchBody(String appliance, boolean on, Integer delaySeconds) {
        String delay = delaySeconds == null ? "" : ",\"delaySeconds\":" + delaySeconds;
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"switch_appliance\",\"arguments\":{"
                + "\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"applianceName\":\"" + appliance + "\",\"on\":" + on + delay + "}}}";
    }

    private static String baseSwitch() {
        return "\"instanceId\":\"11111111-1111-1111-1111-111111111111\",\"applianceName\":\"fridge\",\"on\":false";
    }

    private static long posts() {
        return DashboardStub.recorded.stream().filter(r -> "POST".equals(r.method())).count();
    }
}
