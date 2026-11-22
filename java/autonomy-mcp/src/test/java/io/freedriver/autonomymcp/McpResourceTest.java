package io.freedriver.autonomymcp;

import io.quarkus.redis.datasource.RedisDataSource;
import io.freedriver.mcp.McpEndpoint;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        DashboardStub.exchanges.clear();
        DashboardStub.down.set(false);
        DashboardStub.refusedAppliances.clear();
        DashboardStub.refusedSchedules.clear();
        DashboardStub.pendingCapSchedules.clear();
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
    void list_appliances_sends_the_callers_exchanged_token() {
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
        assertTrue(onlyAsCaller("mcp-reader"));
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "GET".equals(r.method())
                        && "/api/mcp/appliances".equals(r.path())
                        && asCaller(r, "mcp-reader")));
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void the_exchange_asks_keycloak_for_a_dashboard_token_as_freedriver_autonomy_mcp() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(call("home_status"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false));
        assertEquals(1, DashboardStub.exchanges.size(), DashboardStub.exchanges::toString);
        Map<String, List<String>> form = DashboardStub.exchanges.getFirst();
        assertEquals(Map.of(
                "grant_type", List.of("urn:ietf:params:oauth:grant-type:token-exchange"),
                "client_id", List.of("freedriver-autonomy-mcp"),
                "client_secret", List.of("test-exchange-secret"),
                "subject_token", List.of(TestCallerToken.of("mcp-reader")),
                "subject_token_type", List.of("urn:ietf:params:oauth:token-type:access_token"),
                "requested_token_type", List.of("urn:ietf:params:oauth:token-type:access_token"),
                "audience", List.of("freedriver-api")), form);
        assertTrue(onlyAsCaller("mcp-reader"));
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
    void switch_calls_dashboard_as_the_exchanged_caller() {
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
        assertTrue(onlyAsCaller("mcp-switch"));
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "POST".equals(r.method())
                        && r.path().contains("/api/appliances/")
                        && asCaller(r, "mcp-switch")));
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void switch_sends_a_fixture_name_with_a_space() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switchBody("Hallway (test)", false))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Hallway (test) turned off."));
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "POST".equals(r.method())
                        && (r.path().contains("/Hallway (test)")
                                || r.path().contains("/Hallway%20(test)"))
                        && asCaller(r, "mcp-switch")));
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
    void switch_appliances_advertises_a_list_of_entries_and_switch_appliance_is_gone() {
        String session = initialize();
        String tools = given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.name", hasItem("switch_appliances"))
                .body("result.tools.name", not(hasItem("switch_appliance")))
                .body("result.tools.every { it.inputSchema.additionalProperties == false }", is(true))
                .extract().asString();
        JsonPath list = JsonPath.from(tools);
        String tool = "result.tools.find { it.name == 'switch_appliances' }";
        Map<String, Object> schema = list.getMap(tool + ".inputSchema");
        String shown = String.valueOf(schema);
        assertEquals(java.util.Set.of("instanceId", "appliances"), java.util.Set.copyOf(list.getList(tool + ".inputSchema.required")), shown);
        assertEquals("array", list.getString(tool + ".inputSchema.properties.appliances.type"), shown);
        assertEquals(1, list.getInt(tool + ".inputSchema.properties.appliances.minItems"), shown);
        assertEquals(16, list.getInt(tool + ".inputSchema.properties.appliances.maxItems"), shown);
        String item = tool + ".inputSchema.properties.appliances.items";
        assertEquals("object", list.getString(item + ".type"), shown);
        assertEquals(false, list.getBoolean(item + ".additionalProperties"), shown);
        assertEquals(java.util.Set.of("applianceName", "on", "delaySeconds"),
                list.getMap(item + ".properties").keySet(), shown);
        assertEquals("string", list.getString(item + ".properties.applianceName.type"), shown);
        assertEquals(1, list.getInt(item + ".properties.applianceName.minLength"), shown);
        assertEquals(64, list.getInt(item + ".properties.applianceName.maxLength"), shown);
        assertEquals("boolean", list.getString(item + ".properties.on.type"), shown);
        assertEquals("integer", list.getString(item + ".properties.delaySeconds.type"), shown);
        assertEquals(0, list.getInt(item + ".properties.delaySeconds.minimum"), shown);
        assertEquals(86400, list.getInt(item + ".properties.delaySeconds.maximum"), shown);
        assertTrue(list.getList(item + ".required").containsAll(List.of("applianceName", "on")), shown);
        assertFalse(list.getList(item + ".required").contains("delaySeconds"), shown);
        assertTrue(list.getString(tool + ".description").contains("`delaySeconds` 300"), shown);
        assertTrue(list.getString("result.tools.find { it.name == 'list_appliances' }.description")
                .contains("switch_appliances"));
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
                        && asCaller(r, "mcp-switch")
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
     * Pinned answers. Every case is -32602 naming the argument or the constraint. A Bean Validation
     * refusal (the {@code switchAppliances.} messages) also writes a {@code denied} audit line; a value
     * the library cannot convert, or a missing argument, is refused by the library before the tool bean
     * and is not audited.
     */
    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void a_bad_argument_is_invalid_params_and_switches_nothing() {
        String session = initialize();
        String[][] cases = {
            {entries("{\"applianceName\":\"fridge\",\"on\":false,\"delaySeconds\":-1}"),
                "switchAppliances.appliances[0].delaySeconds: must be greater than or equal to 0"},
            {entries("{\"applianceName\":\"fridge\",\"on\":false,\"delaySeconds\":1.5}"),
                "Invalid argument [appliances] - unable to convert value"},
            {entries("{\"applianceName\":\"fridge\",\"on\":false,\"delaySeconds\":86401}"),
                "switchAppliances.appliances[0].delaySeconds: must be less than or equal to 86400"},
            {entries("{\"applianceName\":\"fridge\",\"on\":false,\"delaySeconds\":\"300\"}"),
                "Invalid argument [appliances] - unable to convert value"},
            {entries("{\"applianceName\":\"fridge\",\"on\":\"true\"}"),
                "Invalid argument [appliances] - unable to convert value"},
            {entries("{\"applianceName\":\"fridge\",\"on\":1}"),
                "Invalid argument [appliances] - unable to convert value"},
            {entries("{\"applianceName\":5,\"on\":false}"),
                "Invalid argument [appliances] - unable to convert value"},
            {entries("{\"applianceName\":\" \",\"on\":false}"),
                "switchAppliances.appliances[0].applianceName: must not be blank"},
            {entries("{\"applianceName\":\"" + "x".repeat(65) + "\",\"on\":false}"),
                "switchAppliances.appliances[0].applianceName: size must be between 0 and 64"},
            {entries("{\"applianceName\":\"fridge\"}"),
                "switchAppliances.appliances[0].on: must not be null"},
            {entries("{\"on\":false}"),
                "switchAppliances.appliances[0].applianceName: must not be blank"},
            {entries("null"),
                "switchAppliances.appliances[0].<list element>: must not be null"},
            {entries(),
                "switchAppliances.appliances: must not be empty"},
            {entries(java.util.Collections.nCopies(17, "{\"applianceName\":\"fridge\",\"on\":false}").toArray(String[]::new)),
                "switchAppliances.appliances: size must be between 0 and 16"},
            {SWITCH_TARGET + ",\"appliances\":{\"applianceName\":\"fridge\",\"on\":false}",
                "Invalid argument [appliances] - unable to convert value"},
            {"\"instanceId\":\"abc\",\"appliances\":[{\"applianceName\":\"fridge\",\"on\":false}]",
                "Invalid argument [instanceId] - unable to convert value"},
            {SWITCH_TARGET,
                "Missing required argument: appliances"}
        };
        AuditLines audit = AuditLines.open();
        List<String> answers = new java.util.ArrayList<>();
        for (String[] pin : cases) {
            String answer = given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switchArguments(pin[0]))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .extract().asString();
            answers.add(JsonPath.from(answer).getInt("error.code") + " " + JsonPath.from(answer).getString("error.message"));
        }
        List<String> pinned = java.util.Arrays.stream(cases).map(pin -> "-32602 " + pin[1]).toList();
        assertEquals(pinned, answers, String.join("\n", answers));
        assertEquals(0, posts());
        audit.close();
        long constraintRefusals = java.util.Arrays.stream(cases).filter(pin -> pin[1].startsWith("switchAppliances.")).count();
        assertEquals(constraintRefusals, audit.lines().stream()
                .filter(line -> line.contains("tool=switch_appliances") && line.endsWith("result=denied")).count(),
                audit.lines()::toString);
    }

    /** An argument the tool does not declare, or an entry field the entry does not declare, is refused before the tool runs. */
    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void an_unknown_argument_is_refused_and_nothing_switches() {
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            for (String arguments : new String[] {
                baseSwitch() + ",\"duration\":300",
                entries(entry("porch", true, null), "{\"applianceName\":\"fridge\",\"on\":false,\"duration\":300}")}) {
                given().contentType("application/json")
                        .accept("application/json, text/event-stream")
                        .header(McpEndpoint.SESSION_HEADER, session)
                        .body(switchArguments(arguments))
                        .post("/autonomy/mcp")
                        .then()
                        .statusCode(200)
                        .body("error.code", is(-32602))
                        .body("error.message", is("invalid params"));
            }
            assertEquals(0, posts());
            assertEquals(2, audit.lines().stream().filter(line -> line.contains("sub=mcp-switch")
                    && line.contains("tool=switch_appliances")
                    && line.contains("instance=11111111-1111-1111-1111-111111111111")
                    && line.endsWith("result=denied")).count(), audit.lines()::toString);
        }
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void several_appliances_in_one_call_each_reach_the_dashboard_in_order() {
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switches(entry("fridge", true, null), entry("porch light", false, null), entry("lamp", true, 300)))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(false))
                    .body("result.content[0].text",
                            is("Fridge turned on. Porch light turned off. Lamp will turn on in 5 minutes."));
            List<DashboardStub.Recorded> posted = DashboardStub.recorded.stream()
                    .filter(r -> "POST".equals(r.method())).toList();
            assertEquals(3, posted.size(), posted::toString);
            assertEquals("/api/appliances/11111111-1111-1111-1111-111111111111/fridge", posted.get(0).path());
            assertTrue(posted.get(0).body().contains("\"on\":true"), posted.get(0)::toString);
            assertEquals("/api/appliances/11111111-1111-1111-1111-111111111111/porch light", posted.get(1).path());
            assertTrue(posted.get(1).body().contains("\"on\":false"), posted.get(1)::toString);
            assertEquals("/api/mcp/scheduled-switches", posted.get(2).path());
            assertTrue(posted.get(2).body().contains("\"on\":true") && posted.get(2).body().contains("lamp"),
                    posted.get(2)::toString);
            assertTrue(onlyAsCaller("mcp-switch"));
            assertEquals(List.of("appliance=fridge result=allowed", "appliance=porch light result=allowed",
                    "appliance=lamp result=scheduled"), switchAudit(audit));
        }
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void a_refused_entry_is_reported_with_the_others_outcomes() {
        DashboardStub.refusedAppliances.put("porch", 429);
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switches(entry("fridge", true, null), entry("porch", true, null), entry("lamp", false, null)))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(false))
                    .body("result.content[0].text",
                            is("Fridge turned on. Porch: too many requests. Try again in a minute. Lamp turned off."));
            assertEquals(3, posts());
            assertEquals(List.of("appliance=fridge result=allowed", "appliance=porch result=rate-limited",
                    "appliance=lamp result=allowed"), switchAudit(audit));
        }
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void a_capped_delayed_entry_keeps_the_accepted_entries() {
        DashboardStub.refusedSchedules.put("porch-light", 429);
        DashboardStub.pendingCapSchedules.add("porch-light");
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switches(entry("lamp", true, 300), entry("porch-light", false, 60), entry("fridge", true, null)))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(false))
                    .body("result.content[0].text", is("Lamp will turn on in 5 minutes. "
                            + "Porch light: the pending cap is full until a stored switch fires or is given up "
                            + "after three failed attempts. Fridge turned on."));
            assertEquals(List.of("appliance=lamp result=scheduled", "appliance=porch-light result=pending-cap",
                    "appliance=fridge result=allowed"), switchAudit(audit));
        }
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void every_delayed_entry_at_the_pending_cap_is_a_tool_error() {
        DashboardStub.refusedSchedules.put("porch", 429);
        DashboardStub.refusedSchedules.put("lamp", 429);
        DashboardStub.pendingCapSchedules.add("porch");
        DashboardStub.pendingCapSchedules.add("lamp");
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switches(entry("porch", true, 60), entry("lamp", false, 90)))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(true))
                    .body("result.content[0].text", is(
                            "Porch: the pending cap is full until a stored switch fires or is given up "
                                    + "after three failed attempts. "
                                    + "Lamp: the pending cap is full until a stored switch fires or is given up "
                                    + "after three failed attempts."));
            assertEquals(List.of("appliance=porch result=pending-cap", "appliance=lamp result=pending-cap"),
                    switchAudit(audit));
        }
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void a_delayed_entry_with_a_bare_429_asks_for_a_minute() {
        DashboardStub.refusedSchedules.put("porch", 429);
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switches(entry("porch", true, 60)))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(true))
                    .body("result.content[0].text", is("Porch: too many requests. Try again in a minute."));
            assertEquals(List.of("appliance=porch result=rate-limited"), switchAudit(audit));
        }
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void every_entry_refused_is_a_tool_error() {
        DashboardStub.refusedAppliances.put("porch", 403);
        DashboardStub.refusedAppliances.put("lamp", 409);
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switches(entry("porch", true, null), entry("lamp", true, null)))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(true))
                .body("result.content[0].text", is("Porch: that was refused. Lamp: that house has gone quiet."));
    }

    /** The test profile allows 8 hits per minute. */
    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void a_list_longer_than_the_allowance_left_is_refused_whole() {
        String session = initialize();
        String six = switches(entry("a", true, null), entry("b", true, null), entry("c", true, null),
                entry("d", true, null), entry("e", true, null), entry("f", true, null));
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(six)
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false));
        assertEquals(6, posts());
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switches(entry("g", true, null), entry("h", true, null), entry("i", true, null)))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(true))
                    .body("result.content[0].text", is("Too many requests. Try again in a minute."));
            assertEquals(6, posts(), "the refused call reached the dashboard");
            assertEquals(List.of("appliance=g result=rate-limited", "appliance=h result=rate-limited",
                    "appliance=i result=rate-limited"), switchAudit(audit));
        }
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switches(entry("g", true, null), entry("h", true, null)))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("G turned on. H turned on."));
        assertEquals(8, posts());
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void the_same_appliance_and_delay_twice_is_refused_whole() {
        String session = initialize();
        try (AuditLines audit = AuditLines.open()) {
            given().contentType("application/json")
                    .accept("application/json, text/event-stream")
                    .header(McpEndpoint.SESSION_HEADER, session)
                    .body(switches(entry("porch", true, null), entry("fridge", true, null), entry("fridge", false, 0)))
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(200)
                    .body("result.isError", is(true))
                    .body("result.content[0].text", is("Fridge is listed twice with the same delay. "
                            + "List each appliance once per delay; nothing was switched."));
            assertEquals(0, posts());
            assertEquals(List.of("appliance=porch result=denied", "appliance=fridge result=denied",
                    "appliance=fridge result=denied"), switchAudit(audit));
        }
    }

    @Test
    @TestSecurity(user = "mcp-switch", roles = {"mcp-user", "dashboard"})
    void the_same_appliance_with_different_delays_switches_and_schedules() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switches(entry("fridge", true, null), entry("fridge", false, 300)))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", is("Fridge turned on. Fridge will turn off in 5 minutes."));
        assertEquals(2, posts());
        assertTrue(DashboardStub.recorded.stream().anyMatch(r -> r.path().endsWith("/fridge") && r.body().contains("\"on\":true")));
        assertTrue(DashboardStub.recorded.stream().anyMatch(r -> "/api/mcp/scheduled-switches".equals(r.path())
                && r.body().contains("\"on\":false")));
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
        assertTrue(onlyAsCaller("mcp-reader"));
        assertTrue(DashboardStub.recorded.stream().anyMatch(r ->
                "GET".equals(r.method()) && "/api/mcp/home-status".equals(r.path()) && asCaller(r, "mcp-reader")));
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

    /** The exchanged token never carries portal-admin, so the tool asks for dashboard and nothing else. */
    @Test
    @TestSecurity(user = "mcp-portal", roles = {"mcp-user", "portal-admin"})
    void portal_admin_without_dashboard_cannot_switch() {
        String session = initialize();
        given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(switchArguments(baseSwitch()))
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("error.code", is(-32005))
                .body("error.message", is("io.quarkus.security.ForbiddenException"));
        assertTrue(DashboardStub.recorded.isEmpty(), DashboardStub.recorded::toString);
    }

    @Test
    @TestSecurity(user = "mcp-all", roles = {"mcp-user", "dashboard", "mcp-debug"})
    void dashboard_down_is_spoken_refusal_for_every_tool() {
        DashboardStub.down.set(true);
        String session = initialize();
        Map<String, String> answers = Map.of(
                call("list_appliances"), "The house could not be reached.",
                call("home_status"), "The house could not be reached.",
                call("debug"), "The house could not be reached.",
                switchBody("fridge", false), "Fridge: the house could not be reached.");
        answers.forEach((body, text) -> given().contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body(body)
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(true))
                .body("result.content[0].text", is(text)));
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
        assertTrue(onlyAsCaller("mcp-debug-user"));
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
        return switchArguments(SWITCH_TARGET + ",\"appliances\":[" + entry(appliance, on, delaySeconds) + "]");
    }

    private static String entry(String appliance, boolean on, Integer delaySeconds) {
        String delay = delaySeconds == null ? "" : ",\"delaySeconds\":" + delaySeconds;
        return "{\"applianceName\":\"" + appliance + "\",\"on\":" + on + delay + "}";
    }

    /** A switch_appliances call with these entries, in order. */
    private static String switches(String... entries) {
        return switchArguments(SWITCH_TARGET + ",\"appliances\":[" + String.join(",", entries) + "]");
    }

    private static final String SWITCH_TARGET = "\"instanceId\":\"11111111-1111-1111-1111-111111111111\"";

    private static String baseSwitch() {
        return SWITCH_TARGET + ",\"appliances\":[" + entry("fridge", false, null) + "]";
    }

    private static String switchArguments(String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"switch_appliances\",\"arguments\":{" + arguments + "}}}";
    }

    /** A switch_appliances argument list for this house with these entries, in order. */
    private static String entries(String... entries) {
        return SWITCH_TARGET + ",\"appliances\":[" + String.join(",", entries) + "]";
    }

    /** The appliance and result of each switch_appliances audit line, in order. */
    private static List<String> switchAudit(AuditLines audit) {
        return audit.lines().stream()
                .filter(line -> line.contains("tool=switch_appliances"))
                .map(line -> line.substring(line.indexOf("appliance=")))
                .toList();
    }

    private static long posts() {
        return DashboardStub.recorded.stream().filter(r -> "POST".equals(r.method())).count();
    }

    /** The call carried this caller's token as the stub Keycloak exchanged it, and nothing else. */
    private static boolean asCaller(DashboardStub.Recorded call, String user) {
        return ("Bearer " + DashboardStub.exchanged(TestCallerToken.of(user))).equals(call.authorization());
    }

    /** At least one call reached the stub dashboard, and every one carried this caller's exchanged token. */
    private static boolean onlyAsCaller(String user) {
        return !DashboardStub.recorded.isEmpty()
                && DashboardStub.recorded.stream().allMatch(call -> asCaller(call, user));
    }
}
