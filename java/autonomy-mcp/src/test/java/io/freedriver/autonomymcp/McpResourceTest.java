package io.freedriver.autonomymcp;

import io.quarkus.redis.datasource.RedisDataSource;
import io.freedriver.mcp.McpEndpoint;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    static final String INIT_PARAMS = "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}";
    static final String RESOURCE = "https://app.freedriver.io/autonomy/mcp";
    static final String AUTHORIZATION_SERVER = "https://auth.freedriver.io/realms/freedriver";
    static final String CHALLENGE =
            "Bearer resource_metadata=\"https://app.freedriver.io/api/autonomy/oauth-protected-resource\"";

    @Inject
    RedisDataSource redis;

    @Inject
    ToolRateLimiter limiter;

    @BeforeEach
    void reset() {
        redis.flushall();
        DashboardStub.recorded.clear();
        DashboardStub.down.set(false);
    }

    @Test
    void anonymous_mcp_is_401_without_login_redirect() {
        given().redirects().follow(false)
                .get("/autonomy/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(CHALLENGE))
                .header("Location", nullValue());
        given().redirects().follow(false)
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(CHALLENGE))
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
                .body("resource", is(RESOURCE))
                .body("authorization_servers[0]", is(AUTHORIZATION_SERVER));
    }

    @Test
    @TestSecurity(user = "portal-only", roles = "portal-admin")
    void portal_admin_without_mcp_user_is_403() {
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + INIT_PARAMS + "}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "mcp-session", roles = "mcp-user")
    void a_session_is_bound_to_its_subject_in_redis_for_a_day() {
        String session = initialize();
        String key = "freedriver:mcp:autonomy:" + session;
        assertEquals("mcp-session", redis.value(String.class).get(key));
        long ttl = redis.key().ttl(key);
        assertTrue(ttl > 23 * 3600 && ttl <= 24 * 3600, "ttl " + ttl);

        redis.value(String.class).set(key, "someone-else");
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(call("list_appliances"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(403);
        assertTrue(DashboardStub.recorded.isEmpty());
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void list_appliances_does_not_copy_authorization() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_appliances\",\"arguments\":{}}}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", is(
                        "[{\"houseName\":\"home\",\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                                + "\"appliances\":[{\"name\":\"fridge\",\"state\":\"on\"}]}]"))
                .body("result.content[0].text", not(containsString("Fridge is on.")))
                .body("result.isError", is(false))
                .header("Set-Cookie", nullValue());
        assertFalse(sentAuthorization());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "GET".equals(r.method())
                        && "/api/mcp/appliances".equals(r.path())
                        && r.authorization() == null
                        && "mcp-reader".equals(r.subject())));
    }

    /** Pinned: before the MCP library this was a tool error "That was refused."; now it is JSON-RPC -32005. */
    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void switch_without_dashboard_role_is_refused() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switchBody("fridge", false))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("error.code", is(-32005))
                .body("error.message", is("io.quarkus.security.ForbiddenException"));
        assertTrue(DashboardStub.recorded.isEmpty());
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void switch_calls_dashboard_without_authorization() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switchBody("fridge", false))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Fridge turned off."));
        assertFalse(sentAuthorization());
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
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
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
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.type", is("integer"))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.minimum", is(0))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.maximum", is(86400))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.properties.delaySeconds.description", containsString("300"))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.required", hasItem("on"))
                .body("result.tools.find { it.name == 'switch_appliance' }.inputSchema.required", not(hasItem("delaySeconds")))
                .body("result.tools.every { it.inputSchema.additionalProperties == false }", is(true));
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void delayed_switch_stores_the_row_and_does_not_switch_yet() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switchBody("fridge", false, 300))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Fridge will turn off in 5 minutes."));
        assertEquals(1, posts());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "POST".equals(r.method())
                        && "/api/mcp/scheduled-switches".equals(r.path())
                        && r.authorization() == null
                        && "mcp-switch".equals(r.subject())
                        && r.body().contains("\"on\":false")
                        && r.body().contains("fridge")
                        && r.body().contains("11111111-1111-1111-1111-111111111111")));
        assertTrue(DashboardStub.recorded.stream().noneMatch(r -> r.path().startsWith("/api/appliances/")));
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void delayed_switch_does_not_claim_success_when_the_store_is_down() {
        DashboardStub.down.set(true);
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switchBody("fridge", false, 5))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(true));
        assertEquals(0, posts());
    }

    /**
     * Pinned answers. Before the MCP library every case here was -32602 "invalid params". The library
     * keeps -32602 and names the argument or the constraint. A Bean Validation refusal (the
     * {@code switchAppliance.} messages) also writes a {@code denied} audit line; a value the library
     * cannot convert, or a missing argument, is refused by the library before the tool bean and is not audited.
     */
    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void a_bad_argument_is_invalid_params_and_switches_nothing() {
        String session = initialize();
        String[][] cases = {
            {baseSwitch() + ",\"delaySeconds\":-1", "switchAppliance.delaySeconds: must be greater than or equal to 0"},
            {baseSwitch() + ",\"delaySeconds\":1.5", "Invalid argument [delaySeconds] - unable to convert value"},
            {baseSwitch() + ",\"delaySeconds\":86401", "switchAppliance.delaySeconds: must be less than or equal to 86400"},
            {baseSwitch() + ",\"delaySeconds\":\"300\"", "Invalid argument [delaySeconds] - unable to convert value"},
            {SWITCH_TARGET + ",\"applianceName\":\"fridge\",\"on\":\"true\"", "Invalid argument [on] - unable to convert value"},
            {SWITCH_TARGET + ",\"applianceName\":\"fridge\",\"on\":1", "Invalid argument [on] - unable to convert value"},
            {SWITCH_TARGET + ",\"applianceName\":5,\"on\":false", "Invalid argument [applianceName] - unable to convert value"},
            {SWITCH_TARGET + ",\"applianceName\":\" \",\"on\":false", "switchAppliance.applianceName: must not be blank"},
            {SWITCH_TARGET + ",\"applianceName\":\"" + "x".repeat(65) + "\",\"on\":false",
                "switchAppliance.applianceName: size must be between 0 and 64"},
            {"\"instanceId\":\"abc\",\"applianceName\":\"fridge\",\"on\":false", "Invalid argument [instanceId] - unable to convert value"},
            {SWITCH_TARGET + ",\"applianceName\":\"fridge\"", "Missing required argument: on"}
        };
        AuditLines audit = AuditLines.open();
        for (String[] pin : cases) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switchArguments(pin[0]))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("error.code", is(-32602))
                    .body("error.message", is(pin[1]));
        }
        assertEquals(0, posts());
        audit.close();
        long constraintRefusals = java.util.Arrays.stream(cases).filter(pin -> pin[1].startsWith("switchAppliance.")).count();
        assertEquals(constraintRefusals, audit.lines().stream()
                .filter(line -> line.contains("tool=switch_appliance") && line.endsWith("result=denied")).count(),
                audit.lines()::toString);
    }

    /** Pinned: an argument the tool does not declare is refused before the tool runs, as before the MCP library. */
    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void an_unknown_argument_is_refused_and_nothing_switches() {
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switchArguments(baseSwitch() + ",\"duration\":300"))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("error.code", is(-32602))
                    .body("error.message", is("invalid params"));
            assertEquals(0, posts());
            assertTrue(audit.lines().stream().anyMatch(line -> line.contains("sub=mcp-switch")
                    && line.contains("tool=switch_appliance")
                    && line.contains("instance=11111111-1111-1111-1111-111111111111")
                    && line.contains("appliance=fridge")
                    && line.endsWith("result=denied")), audit.lines()::toString);
        }
    }

    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void an_unknown_argument_is_ignored_and_the_switch_runs_now() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switchArguments(baseSwitch() + ",\"duration\":300"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Fridge turned off."));
        assertEquals(1, posts());
        assertTrue(DashboardStub.recorded.stream().noneMatch(r -> r.path().equals("/api/mcp/scheduled-switches")));
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void home_status_is_spoken() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(call("home_status"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", containsString("The broker is connected."))
                .body("result.content[0].text", containsString("Last state update"));
        assertFalse(sentAuthorization());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "GET".equals(r.method()) && "/api/mcp/home-status".equals(r.path()) && r.authorization() == null));
    }

    /** Pinned: before the MCP library this was a tool error "That was refused."; now it is JSON-RPC -32005. */
    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void debug_without_mcp_debug_is_refused() {
        String session = initialize();
        AuditLines audit = AuditLines.open();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(call("debug"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("error.code", is(-32005))
                .body("error.message", is("io.quarkus.security.ForbiddenException"));
        assertTrue(DashboardStub.recorded.isEmpty());
        audit.close();
        assertTrue(audit.lines().stream().anyMatch(line -> line.contains("sub=mcp-reader")
                && line.contains("tool=debug") && line.endsWith("result=denied")), audit.lines()::toString);
    }

    @Test
    @TestSecurity(user = "mcp-all", roles = {"mcp-user", "dashboard", "mcp-debug"})
    void dashboard_down_is_spoken_refusal_for_every_tool() {
        DashboardStub.down.set(true);
        String session = initialize();
        for (String body : new String[] {call("list_appliances"), call("home_status"), call("debug"), switchBody("fridge", false)}) {
            given().contentType("application/json")
                .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
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
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(call("debug"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", containsString("Health UP"))
                .body("result.content[0].text", containsString("none"));
        assertFalse(sentAuthorization());
    }

    private static String initialize() {
        return given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + INIT_PARAMS + "}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .extract()
                .header(McpEndpoint.SESSION_HEADER);
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

    private static final String SWITCH_TARGET = "\"instanceId\":\"11111111-1111-1111-1111-111111111111\"";

    private static String baseSwitch() {
        return SWITCH_TARGET + ",\"applianceName\":\"fridge\",\"on\":false";
    }

    private static String switchArguments(String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"switch_appliance\",\"arguments\":{" + arguments + "}}}";
    }

    private static long posts() {
        return DashboardStub.recorded.stream().filter(r -> "POST".equals(r.method())).count();
    }

    /** True when any call that reached the stub dashboard carried an Authorization header. */
    private static boolean sentAuthorization() {
        return DashboardStub.recorded.stream().anyMatch(call -> call.authorization() != null);
    }
}
