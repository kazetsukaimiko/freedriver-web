package io.freedriver.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.dispatcher.api.McpApi;
import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class McpResourceTest {

    private static final String PROMPT = "secret-prompt-do-not-leak";

    @Inject
    JobStore jobs;

    @Inject
    McpSessions sessions;

    @Inject
    ObjectMapper mapper;

    @Test
    void anonymous_mcp_is_401_and_the_tool_does_not_run() {
        jobs.register("pc-anon", "anon.local", List.of("yuni"));
        int before = jobs.jobCount();

        String posted = given()
                .contentType("application/json")
                .body(submitBody("pc-anon", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE))
                .extract()
                .asString();

        String got = given()
                .get("/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE))
                .extract()
                .asString();

        assertFalse(posted.contains(PROMPT));
        assertFalse(got.contains(PROMPT));
        assertFalse(posted.contains("authorization_servers"));
        assertEquals(before, jobs.jobCount());
        given().get("/mcp/sse").then().statusCode(401);
        given().post("/mcp/oauth-protected-resource").then().statusCode(401);
        given()
                .contentType("application/json")
                .body(submitBody("pc-anon", PROMPT))
                .post("/dispatch/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE));
        given().get("/dispatch/mcp").then().statusCode(401).header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE));
    }

    @Test
    void session_without_a_token_is_401() {
        String sessionId = sessions.open("alice-bare");
        int before = jobs.jobCount();

        given()
                .contentType("application/json")
                .header(McpApi.SESSION_HEADER, sessionId)
                .body(submitBody("pc-anon", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(401);

        assertEquals(before, jobs.jobCount());
        assertEquals(McpSessions.Status.MATCH, sessions.check(sessionId, "alice-bare"));
    }

    @Test
    @TestSecurity(user = "pc-mcp", roles = "device")
    void device_role_is_403_and_the_tool_does_not_run() {
        jobs.register("pc-device-role", "device.local", List.of("yuni"));
        int before = jobs.jobCount();

        given()
                .contentType("application/json")
                .body(submitBody("pc-device-role", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(403);
        given()
                .contentType("application/json")
                .body(submitBody("pc-device-role", PROMPT))
                .post("/dispatch/mcp")
                .then()
                .statusCode(403);

        assertEquals(before, jobs.jobCount());
    }

    @Test
    @TestSecurity(user = "bob-session", roles = "dispatcher")
    void session_for_another_subject_is_403_and_the_tool_does_not_run() {
        String alice = sessions.open("alice-session");
        jobs.register("pc-bob-session", "bob.local", List.of("yuni"));
        int before = jobs.jobCount();

        String body = given()
                .contentType("application/json")
                .header(McpApi.SESSION_HEADER, alice)
                .body(submitBody("pc-bob-session", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(403)
                .extract()
                .asString();

        assertFalse(body.contains(PROMPT));
        assertEquals(before, jobs.jobCount());
        assertEquals(McpSessions.Status.MATCH, sessions.check(alice, "alice-session"));
        assertEquals(McpSessions.Status.OTHER_SUBJECT, sessions.check(alice, "bob-session"));
    }

    @Test
    @TestSecurity(user = "bob-delete", roles = "dispatcher")
    void delete_of_another_subjects_session_is_403() {
        String alice = sessions.open("alice-delete");

        given()
                .header(McpApi.SESSION_HEADER, alice)
                .delete("/mcp")
                .then()
                .statusCode(403);

        assertEquals(McpSessions.Status.MATCH, sessions.check(alice, "alice-delete"));
    }

    @Test
    @TestSecurity(user = "grok-no-session", roles = "dispatcher")
    void dispatcher_without_a_session_does_not_run_the_tool() {
        jobs.register("pc-no-session", "nosession.local", List.of("yuni"));
        int before = jobs.jobCount();

        given()
                .contentType("application/json")
                .body(submitBody("pc-no-session", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(400);

        assertEquals(before, jobs.jobCount());
    }

    @Test
    @TestSecurity(user = "grok-unknown", roles = "dispatcher")
    void unknown_session_is_404_and_the_tool_does_not_run() {
        jobs.register("pc-unknown-session", "unknown.local", List.of("yuni"));
        int before = jobs.jobCount();

        given()
                .contentType("application/json")
                .header(McpApi.SESSION_HEADER, "not-a-real-session")
                .body(submitBody("pc-unknown-session", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(404);

        assertEquals(before, jobs.jobCount());
    }

    @Test
    @TestSecurity(user = "grok-mcp", roles = "dispatcher")
    void dispatcher_tools_expire_and_legacy_sse_stays_off() throws Exception {
        jobs.register("pc-mcp-grok", "grok.local", List.of("yuni"));
        String session = initialize();

        ValidatableResponse listed = call(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .statusCode(200)
                .contentType("application/json")
                .body("result.tools.name", containsInAnyOrder("list_devices", "submit_job", "get_job"))
                .body("result.tools.size()", is(3))
                .body("result.tools.find { it.name == 'submit_job' }.inputSchema.properties.prompt.maxLength", is(16000))
                .body("result.tools.find { it.name == 'submit_job' }.inputSchema.properties.deviceIds.maxItems", is(32))
                .body("result.tools.find { it.name == 'submit_job' }.inputSchema.additionalProperties", is(false))
                .body("result.tools.find { it.name == 'list_devices' }.inputSchema.additionalProperties", is(false));
        String toolsJson = listed.extract().asString();
        assertFalse(toolsJson.contains("allowlist"));
        assertFalse(toolsJson.contains("\"shell\""));
        assertFalse(toolsJson.contains("\"environment\""));
        assertFalse(toolsJson.contains("grok"));
        assertFalse(toolsJson.contains("\"resources\""));
        assertFalse(toolsJson.contains("\"prompts\""));

        String devicesText = call(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_devices\",\"arguments\":{}}}")
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode devices = mapper.readTree(devicesText);
        JsonNode device = findDevice(devices, "pc-mcp-grok");
        assertEquals(Set.of("deviceId", "hostname", "agents", "lastHeartbeat"), fieldNames(device));
        assertEquals("grok.local", device.path("hostname").asText());
        assertEquals("yuni", device.path("agents").get(0).asText());

        String submittedText = call(session, submitBody("pc-mcp-grok", "say hello"))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode submitted = mapper.readTree(submittedText);
        assertEquals("EXPIRED", submitted.path("assignments").get(0).path("state").asText());
        String jobId = submitted.path("jobId").asText();

        String ownJob = call(session, getJob(jobId))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        assertTrue(ownJob.contains("say hello"));
        assertTrue(ownJob.contains(jobId));

        call(session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .statusCode(202);

        call(session, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}")
                .statusCode(200)
                .body("error.code", is(-32601));
        call(session, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\",\"params\":{\"name\":\"host\"}}")
                .statusCode(200)
                .body("error.code", is(-32601));

        int before = jobs.jobCount();
        call(session, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"shell\",\"arguments\":{\"command\":\"uname\"}}}")
                .statusCode(200)
                .body("error.code", is(-32602));
        assertEquals(before, jobs.jobCount());

        String tooLong = "P" + "x".repeat(16000);
        String rejected = call(session, submitBody("pc-mcp-grok", tooLong))
                .statusCode(200)
                .body("error.code", is(-32602))
                .extract()
                .asString();
        assertFalse(rejected.contains(tooLong));
        assertEquals(before, jobs.jobCount());

        String shelled = call(session, """
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"submit_job","arguments":{"deviceIds":["pc-mcp-grok"],"agentName":"yuni","prompt":"hi","channel":"TEXT","shell":"rm -rf /"}}}
                """)
                .statusCode(200)
                .body("error.code", is(-32602))
                .extract()
                .asString();
        assertFalse(shelled.contains("rm -rf"));
        assertEquals(before, jobs.jobCount());

        call(session, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}")
                .statusCode(200);

        given()
                .header(McpApi.SESSION_HEADER, session)
                .header("MCP-Protocol-Version", "2024-11-05")
                .contentType("application/json")
                .body(submitBody("pc-mcp-grok", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(400);
        assertEquals(before, jobs.jobCount());

        Response stream = given()
                .header(McpApi.SESSION_HEADER, session)
                .get("/mcp")
                .then()
                .statusCode(405)
                .extract()
                .response();
        assertTrue(stream.getContentType() == null || !stream.getContentType().contains("event-stream"));

        given().get("/mcp/sse").then().statusCode(404);
        given().post("/mcp/sse").then().statusCode(404);
        given().get("/mcp/messages").then().statusCode(404);

        given()
                .header(McpApi.SESSION_HEADER, session)
                .delete("/mcp")
                .then()
                .statusCode(204);
        given()
                .contentType("application/json")
                .header(McpApi.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\"}")
                .post("/mcp")
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "bob-job", roles = "dispatcher")
    void get_job_for_another_subject_does_not_return_the_prompt() throws Exception {
        jobs.register("pc-secret", "secret.local", List.of("yuni"));
        JobView hidden = jobs.submit(
                "alice-job",
                new SubmitJobRequest(List.of("pc-secret"), "yuni", PROMPT, Channel.TEXT),
                Duration.ZERO);
        assertEquals(AssignmentState.EXPIRED, hidden.assignments().get(0).state());
        String session = initialize();

        String body = call(session, getJob(hidden.jobId().toString()))
                .statusCode(200)
                .body("result.isError", is(true))
                .extract()
                .asString();

        assertFalse(body.contains(PROMPT));
        assertTrue(body.contains("job not found"));

        String listed = call(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_devices\",\"arguments\":{}}}")
                .statusCode(200)
                .extract()
                .path("result.content[0].text");
        assertFalse(listed.contains(PROMPT));
    }

    @Test
    @TestSecurity(user = "grok-cap", roles = "dispatcher")
    void mcp_submit_conflicts_while_the_subject_already_has_one_in_flight() throws Exception {
        jobs.register("pc-cap-mcp", "cap.local", List.of("yuni"));
        String session = initialize();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> jobs.submit(
                    "grok-cap",
                    new SubmitJobRequest(List.of("pc-cap-mcp"), "yuni", "background", Channel.TEXT),
                    Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued("pc-cap-mcp");
            int before = jobs.jobCount();

            String body = call(session, submitBody("pc-cap-mcp", "second"))
                    .statusCode(200)
                    .body("result.isError", is(true))
                    .extract()
                    .asString();

            assertTrue(body.contains("submit already in flight"));
            assertFalse(body.contains("background"));
            assertEquals(before, jobs.jobCount());
            UUID grokSession = UUID.randomUUID();
            jobs.claim("pc-cap-mcp", queued.assignmentId());
            jobs.update("pc-cap-mcp", queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.RUNNING, grokSession, null));
            jobs.update("pc-cap-mcp", queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, grokSession, null));
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private String initialize() {
        return given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                        """)
                .post("/mcp")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("result.protocolVersion", is("2025-11-25"))
                .body("result.capabilities.tools.listChanged", is(false))
                .body("result.capabilities.resources", nullValue())
                .body("result.capabilities.prompts", nullValue())
                .body("result.serverInfo.name", is("freedriver-dispatcher"))
                .extract()
                .header(McpApi.SESSION_HEADER);
    }

    private static ValidatableResponse call(String sessionId, String body) {
        return given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpApi.SESSION_HEADER, sessionId)
                .body(body)
                .post("/mcp")
                .then();
    }

    private static String submitBody(String deviceId, String prompt) {
        return """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"submit_job","arguments":{"deviceIds":["%s"],"agentName":"yuni","prompt":"%s","channel":"TEXT"}}}
                """.formatted(deviceId, prompt);
    }

    private static String getJob(String jobId) {
        return """
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"get_job","arguments":{"jobId":"%s"}}}
                """.formatted(jobId);
    }

    private AssignmentView waitForQueued(String deviceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<AssignmentView> queued = jobs.poll(deviceId, Duration.ZERO);
            if (!queued.isEmpty()) {
                return queued.get(0);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("assignment was not queued");
    }

    private static JsonNode findDevice(JsonNode devices, String deviceId) {
        for (JsonNode device : devices) {
            if (deviceId.equals(device.path("deviceId").asText())) {
                return device;
            }
        }
        throw new AssertionError(deviceId);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
