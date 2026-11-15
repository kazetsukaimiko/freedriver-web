package io.freedriver.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.dispatcher.model.AgentRosterEntry;
import io.freedriver.mcp.McpEndpoint;
import io.freedriver.mcp.McpSessions;
import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class McpResourceTest {

    private static final String CHALLENGE =
            "Bearer resource_metadata=\"https://app.freedriver.io/api/dispatcher/oauth-protected-resource\"";

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
        int before = jobCount();

        String posted = given()
                .contentType("application/json")
                .body(submitBody("pc-anon", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(CHALLENGE))
                .extract()
                .asString();

        String got = given()
                .get("/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(CHALLENGE))
                .extract()
                .asString();

        assertFalse(posted.contains(PROMPT));
        assertFalse(got.contains(PROMPT));
        assertFalse(posted.contains("authorization_servers"));
        assertEquals(before, jobCount());
        given().get("/mcp/sse").then().statusCode(401);
        given().post("/mcp/oauth-protected-resource").then().statusCode(401);
        given()
                .contentType("application/json")
                .body(submitBody("pc-anon", PROMPT))
                .post("/dispatch/mcp")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is(CHALLENGE));
        given().get("/dispatch/mcp").then().statusCode(401).header("WWW-Authenticate", is(CHALLENGE));
    }

    @Test
    void session_without_a_token_is_401() {
        String sessionId = UUID.randomUUID().toString();
        sessions.remember(sessionId, "alice-bare").await().indefinitely();
        int before = jobCount();

        given()
                .contentType("application/json")
                .header(McpEndpoint.SESSION_HEADER, sessionId)
                .body(submitBody("pc-anon", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(401);

        assertEquals(before, jobCount());
        assertEquals(McpSessions.Status.MATCH, sessions.check(sessionId, "alice-bare").await().indefinitely());
    }

    @Test
    @TestSecurity(user = "freedriver-openbot", roles = "device")
    void host_reports_bots_and_finishes_the_job() throws Exception {
        String session = initialize();
        call(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .statusCode(200)
                .body("result.tools.name", containsInAnyOrder("report_host", "finish_job"))
                .body("result.tools.size()", is(2));

        int before = jobCount();
        call(session, submitBody("freedriver-openbot", PROMPT))
                .statusCode(200)
                .body("error.code", is(-32005));
        assertEquals(before, jobCount());
        assertFalse(call(session, submitBody("freedriver-openbot", PROMPT)).extract().asString().contains(PROMPT));

        call(session, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"report_host","arguments":{"hostname":"this-pc","bots":[{"name":"Project Manager","state":"idle","activity":"waiting on a job"}]}}}
                """)
                .statusCode(200)
                .body("result.isError", is(false));

        AgentRosterEntry row = jobs.listAgents().stream()
                .filter(entry -> "Project Manager".equals(entry.name()) && "this-pc".equals(entry.server()))
                .findFirst()
                .orElseThrow();
        assertEquals("bot", row.type());
        assertEquals("IDLE", row.state());
        assertEquals("waiting on a job", row.activity());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> jobs.submit(
                    "grok-user",
                    new SubmitJobRequest(List.of("freedriver-openbot"), "Project Manager", "say hello", Channel.TEXT),
                    Duration.ofSeconds(2)));
            AssignmentView queued = waitForQueued("freedriver-openbot");
            String pendingJobs = call(session, """
                    {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"report_host","arguments":{"hostname":"this-pc","bots":[{"name":"Project Manager","state":"idle"}]}}}
                    """)
                    .statusCode(200)
                    .body("result.isError", is(false))
                    .extract()
                    .path("result.content[0].text");
            assertTrue(pendingJobs.contains(queued.assignmentId().toString()));
            assertTrue(pendingJobs.contains("QUEUED"));
            call(session, """
                    {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"finish_job","arguments":{"assignmentId":"%s","succeeded":true,"note":"done"}}}
                    """.formatted(queued.assignmentId()))
                    .statusCode(200)
                    .body("result.isError", is(false));
            JobView done = pending.get(5, TimeUnit.SECONDS);
            assertEquals(AssignmentState.SUCCEEDED, done.assignments().get(0).state());
            assertEquals("done", done.assignments().get(0).note());
            String after = call(session, """
                    {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"report_host","arguments":{"hostname":"this-pc","bots":[{"name":"Project Manager","state":"idle"}]}}}
                    """)
                    .statusCode(200)
                    .extract()
                    .path("result.content[0].text");
            assertFalse(after.contains(queued.assignmentId().toString()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @TestSecurity(user = "bob-session", roles = "dispatcher")
    void session_for_another_subject_is_403_and_the_tool_does_not_run() {
        String alice = UUID.randomUUID().toString();
        sessions.remember(alice, "alice-session").await().indefinitely();
        jobs.register("pc-bob-session", "bob.local", List.of("yuni"));
        int before = jobCount();

        String body = given()
                .contentType("application/json")
                .header(McpEndpoint.SESSION_HEADER, alice)
                .body(submitBody("pc-bob-session", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(403)
                .extract()
                .asString();

        assertFalse(body.contains(PROMPT));
        assertEquals(before, jobCount());
        assertEquals(McpSessions.Status.MATCH, sessions.check(alice, "alice-session").await().indefinitely());
        assertEquals(McpSessions.Status.OTHER_SUBJECT, sessions.check(alice, "bob-session").await().indefinitely());
    }

    @Test
    @TestSecurity(user = "bob-delete", roles = "dispatcher")
    void delete_of_another_subjects_session_is_403() {
        String alice = UUID.randomUUID().toString();
        sessions.remember(alice, "alice-delete").await().indefinitely();

        given()
                .header(McpEndpoint.SESSION_HEADER, alice)
                .delete("/mcp")
                .then()
                .statusCode(403);

        assertEquals(McpSessions.Status.MATCH, sessions.check(alice, "alice-delete").await().indefinitely());
    }

    @Test
    @TestSecurity(user = "grok-no-session", roles = "dispatcher")
    void dispatcher_without_a_session_does_not_run_the_tool() {
        jobs.register("pc-no-session", "nosession.local", List.of("yuni"));
        int before = jobCount();

        String body = given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body(submitBody("pc-no-session", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertFalse(body.contains(PROMPT));
        assertEquals(before, jobCount());
    }

    @Test
    @TestSecurity(user = "grok-list", roles = "dispatcher")
    void dispatcher_lists_tools_without_a_session() {
        String session = initialize();
        String listed = call(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .statusCode(200)
                .body("result.tools.name", containsInAnyOrder("list_agents", "openbot_send_prompt", "openbot_await_turn", "get_job", "list_jobs"))
                .extract()
                .asString();
        assertTrue(listed.contains("Freedriver dispatch"));
        assertFalse(listed.contains("allowlist"));

        call(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")
                .statusCode(200);

        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, "not-a-real-session")
                .body("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}")
                .post("/mcp")
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "grok-unknown", roles = "dispatcher")
    void unknown_session_is_404_and_the_tool_does_not_run() {
        jobs.register("pc-unknown-session", "unknown.local", List.of("yuni"));
        int before = jobCount();

        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, "not-a-real-session")
                .body(submitBody("pc-unknown-session", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(404);

        assertEquals(before, jobCount());
    }

    @Test
    @TestSecurity(user = "grok-mcp", roles = "dispatcher")
    void dispatcher_tools_expire_and_legacy_sse_stays_off() throws Exception {
        jobs.register("pc-mcp-grok", "grok.local", List.of("yuni"));
        String session = initialize();

        ValidatableResponse listed = call(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .statusCode(200)
                .contentType("application/json")
                .body("result.tools.name", containsInAnyOrder("list_agents", "openbot_send_prompt", "openbot_await_turn", "get_job", "list_jobs"))
                .body("result.tools.size()", is(5))
                .body("result.tools.find { it.name == 'openbot_send_prompt' }.inputSchema.properties.prompt.maxLength", is(16000))
                .body("result.tools.find { it.name == 'openbot_send_prompt' }.inputSchema.properties.server.maxLength", is(253))
                .body("result.tools.find { it.name == 'openbot_send_prompt' }.inputSchema.properties.blockForUpTo", nullValue())
                .body("result.tools.find { it.name == 'openbot_send_prompt' }.inputSchema.properties.on_busy.enum", hasItems("supersede", "queue"))
                .body("result.tools.find { it.name == 'openbot_send_prompt' }.inputSchema.required", not(hasItem("on_busy")))
                .body("result.tools.find { it.name == 'openbot_await_turn' }.inputSchema.properties.delaySeconds.maximum", is(120))
                .body("result.tools.find { it.name == 'openbot_await_turn' }.inputSchema.properties.delaySeconds.minimum", is(0))
                .body("result.tools.find { it.name == 'openbot_await_turn' }.inputSchema.required", hasItem("delaySeconds"))
                .body("result.tools.find { it.name == 'list_jobs' }.inputSchema.properties.limit.maximum", is(50))
                .body("result.tools.find { it.name == 'list_jobs' }.inputSchema.properties.state.pattern",
                        is("queued|in-progress|done"))
                .body("result.tools.find { it.name == 'list_jobs' }.inputSchema.required", empty())
                .body("result.tools.every { it.inputSchema.additionalProperties == false }", is(true));
        String toolsJson = listed.extract().asString();
        assertFalse(toolsJson.contains("allowlist"));
        assertFalse(toolsJson.contains("\"shell\""));
        assertFalse(toolsJson.contains("\"environment\""));
        assertFalse(toolsJson.contains("grok"));
        assertFalse(toolsJson.contains("\"resources\""));
        assertFalse(toolsJson.contains("\"prompts\""));

        String agentsText = call(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_agents\",\"arguments\":{}}}")
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode agents = mapper.readTree(agentsText);
        assertTrue(agents.isArray());
        JsonNode agent = null;
        for (JsonNode row : agents) {
            if ("yuni".equals(row.path("name").asText()) && "grok.local".equals(row.path("server").asText())) {
                agent = row;
            }
        }
        assertTrue(agent != null);
        assertEquals(Set.of("type", "name", "server", "state", "activity"), fieldNames(agent));
        assertEquals("bot", agent.path("type").asText());
        assertEquals("yuni", agent.path("name").asText());
        assertEquals("grok.local", agent.path("server").asText());
        assertEquals("IDLE", agent.path("state").asText());
        assertEquals("", agent.path("activity").asText());

        jobs.report("pc-mcp-grok", "grok.local", List.of(new ReportedBot("yuni", "busy", "reviewing a pull request")));
        String busyText = call(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_agents\",\"arguments\":{}}}")
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode busy = null;
        for (JsonNode row : mapper.readTree(busyText)) {
            if ("yuni".equals(row.path("name").asText()) && "grok.local".equals(row.path("server").asText())) {
                busy = row;
            }
        }
        assertTrue(busy != null);
        assertEquals("BUSY", busy.path("state").asText());
        assertEquals("reviewing a pull request", busy.path("activity").asText());

        String submittedText = call(session, submitBody("grok.local", "say hello"))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode submitted = mapper.readTree(submittedText);
        assertEquals("QUEUED", submitted.path("assignments").get(0).path("state").asText());
        String jobId = submitted.path("jobId").asText();
        AssignmentView claimed = jobs.claim(
                "pc-mcp-grok",
                UUID.fromString(submitted.path("assignments").get(0).path("assignmentId").asText()));
        assertEquals(AssignmentState.CLAIMED, claimed.state());
        jobs.finish("pc-mcp-grok", claimed.assignmentId(), true, "done");

        String ownJob = call(session, getJob(jobId))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        assertTrue(ownJob.contains("say hello"));
        assertTrue(ownJob.contains(jobId));
        assertTrue(ownJob.contains("SUCCEEDED"));

        call(session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .statusCode(202);

        call(session, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}")
                .statusCode(200)
                .body("result.resources.size()", is(0));
        call(session, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\",\"params\":{\"name\":\"host\"}}")
                .statusCode(200)
                .body("error.code", is(-32602));

        int before = jobCount();
        call(session, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"shell\",\"arguments\":{\"command\":\"uname\"}}}")
                .statusCode(200)
                .body("error.code", is(-32602));
        assertEquals(before, jobCount());

        String tooLong = "P" + "x".repeat(16000);
        String rejected = call(session, submitBody("pc-mcp-grok", tooLong))
                .statusCode(200)
                .body("error.code", is(-32602))
                .extract()
                .asString();
        assertFalse(rejected.contains(tooLong));
        assertEquals(before, jobCount());

        // An argument the tool does not declare is refused before the tool runs: no job, one denied audit line.
        try (AuditLines audit = AuditLines.open()) {
            String shelled = call(session, """
                    {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"openbot_send_prompt","arguments":{"server":"grok.local","agentName":"yuni","prompt":"hi","channel":"TEXT","shell":"rm -rf /"}}}
                    """)
                    .statusCode(200)
                    .body("error.code", is(-32602))
                    .body("error.message", is("invalid params"))
                    .extract()
                    .asString();
            assertFalse(shelled.contains("rm -rf"));
            assertEquals(before, jobCount());
            assertTrue(audit.lines().stream().anyMatch(line -> line.contains("tool=openbot_send_prompt")
                    && line.endsWith("result=denied")), audit.lines()::toString);
            assertFalse(String.join("\n", audit.lines()).contains("rm -rf"));
        }

        call(session, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}")
                .statusCode(200);

        given()
                .header(McpEndpoint.SESSION_HEADER, session)
                .header("MCP-Protocol-Version", "1999-01-01")
                .accept("application/json, text/event-stream")
                .contentType("application/json")
                .body(submitBody("pc-mcp-grok", PROMPT))
                .post("/mcp")
                .then()
                .statusCode(400);
        assertEquals(before, jobCount());

        given()
                .get("/mcp")
                .then()
                .statusCode(405);

        given().post("/mcp/sse").then().statusCode(405);
        given().get("/mcp/messages").then().statusCode(404);

        call(session, "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}")
                .statusCode(200);
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}")
                .post("/dispatch/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.name", containsInAnyOrder("list_agents", "openbot_send_prompt", "openbot_await_turn", "get_job", "list_jobs"));

        given()
                .header(McpEndpoint.SESSION_HEADER, session)
                .delete("/mcp")
                .then()
                .statusCode(200);
        given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\"}")
                .post("/mcp")
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "grok-block", roles = "dispatcher")
    void send_returns_an_id_without_waiting_and_await_follows_that_job() throws Exception {
        jobs.register("pc-block", "block.local", List.of("yuni"));
        String session = initialize();
        int before = jobCount();

        String firstText = call(session, submitBody("block.local", "one"))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        String secondText = call(session, submitBody("block.local", "two"))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode first = mapper.readTree(firstText);
        JsonNode second = mapper.readTree(secondText);
        assertEquals("QUEUED", first.path("assignments").get(0).path("state").asText());
        assertEquals("QUEUED", second.path("assignments").get(0).path("state").asText());
        assertNotEquals(first.path("jobId").asText(), second.path("jobId").asText());
        assertEquals(before + 2, jobCount());

        String rejected = call(session, awaitBody(first.path("jobId").asText(), 121))
                .statusCode(200)
                .body("error.code", is(-32602))
                .extract()
                .asString();
        assertFalse(rejected.contains("one"));
        assertEquals("QUEUED", jobs.getJob("grok-block", UUID.fromString(first.path("jobId").asText()))
                .assignments().get(0).state().name());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> pending = pool.submit(() -> call(session, awaitBody(first.path("jobId").asText(), 30))
                    .statusCode(200)
                    .body("result.isError", is(false))
                    .extract()
                    .path("result.content[0].text"));
            AssignmentView queued = jobs.poll("pc-block", Duration.ZERO).stream()
                    .filter(row -> first.path("jobId").asText().equals(row.jobId().toString()))
                    .findFirst()
                    .orElseThrow();
            jobs.finish("pc-block", queued.assignmentId(), true, "finished in time");
            JsonNode finished = mapper.readTree(pending.get(5, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", finished.path("assignments").get(0).path("state").asText());
            assertEquals("finished in time", finished.path("assignments").get(0).path("note").asText());
        } finally {
            pool.shutdownNow();
        }

        String timedOut = call(session, awaitBody(second.path("jobId").asText(), 1))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        assertEquals("QUEUED", mapper.readTree(timedOut).path("assignments").get(0).path("state").asText());
        assertEquals("supersede", first.path("assignments").get(0).path("on_busy").asText());
    }

    @Test
    @TestSecurity(user = "grok-on-busy", roles = "dispatcher")
    void on_busy_defaults_to_supersede_and_rejects_anything_else() throws Exception {
        jobs.register("pc-on-busy", "on-busy.local", List.of("yuni"));
        String session = initialize();
        int before = jobCount();

        String omitted = call(session, submitBody("on-busy.local", "plain"))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        assertEquals("supersede", mapper.readTree(omitted).path("assignments").get(0).path("on_busy").asText());

        String nulled = call(session, """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"openbot_send_prompt","arguments":{"server":"on-busy.local","agentName":"yuni","prompt":"empty","channel":"TEXT","on_busy":null}}}
                """)
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        assertEquals("supersede", mapper.readTree(nulled).path("assignments").get(0).path("on_busy").asText());

        String queued = call(session, """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"openbot_send_prompt","arguments":{"server":"on-busy.local","agentName":"yuni","prompt":"behind","channel":"TEXT","on_busy":"queue"}}}
                """)
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode queuedJob = mapper.readTree(queued);
        assertEquals("queue", queuedJob.path("assignments").get(0).path("on_busy").asText());
        assertEquals(OnBusy.queue, jobs.poll("pc-on-busy", Duration.ZERO).stream()
                .filter(row -> queuedJob.path("jobId").asText().equals(row.jobId().toString()))
                .findFirst()
                .orElseThrow()
                .onBusy());

        int written = jobCount();
        assertEquals(before + 3, written);
        call(session, """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"openbot_send_prompt","arguments":{"server":"on-busy.local","agentName":"yuni","prompt":"nope","channel":"TEXT","on_busy":"interrupt"}}}
                """)
                .statusCode(200)
                .body("error.code", is(-32602))
                .body(not(containsString("nope")));
        call(session, """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"openbot_send_prompt","arguments":{"server":"on-busy.local","agentName":"yuni","prompt":"nope","channel":"TEXT","on_busy":1}}}
                """)
                .statusCode(200)
                .body("error.code", is(-32602))
                .body(not(containsString("nope")));
        assertEquals(written, jobCount());
    }

    @Test
    @TestSecurity(user = "pc-busy-flag", roles = "device")
    void report_host_includes_on_busy_for_queued_jobs() throws Exception {
        jobs.register("pc-busy-flag", "busy-flag.local", List.of("yuni"));
        jobs.submitNow("grok", new SubmitJobRequest(
                List.of("pc-busy-flag"), "yuni", "behind", Channel.TEXT, OnBusy.queue));
        String session = initialize();
        String text = call(session, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"report_host","arguments":{"hostname":"busy-flag.local","bots":[{"name":"yuni","state":"busy","activity":"on a job"}]}}}
                """)
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode pending = mapper.readTree(text).path("jobs");
        assertEquals(1, pending.size());
        assertEquals("QUEUED", pending.get(0).path("state").asText());
        assertEquals("queue", pending.get(0).path("on_busy").asText());
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

        String listed = call(session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_agents\",\"arguments\":{}}}")
                .statusCode(200)
                .extract()
                .path("result.content[0].text");
        assertFalse(listed.contains(PROMPT));
    }

    @Test
    @TestSecurity(user = "grok-pages", roles = "dispatcher")
    void list_jobs_returns_this_callers_rows_and_not_another_prompt() throws Exception {
        jobs.register("pc-pages", "pages.local", List.of("yuni", "miko"));
        JobView mine = jobs.submitNow("grok-pages", new SubmitJobRequest(
                List.of("pc-pages"), "yuni", "secret-prompt-mine", Channel.TEXT, OnBusy.queue));
        jobs.finish("pc-pages", mine.assignments().get(0).assignmentId(), true, "finished-note");
        JobView queued = jobs.submitNow("grok-pages", new SubmitJobRequest(
                List.of("pc-pages"), "miko", "secret-prompt-queued", Channel.TEXT, OnBusy.queue));
        JobView hidden = jobs.submitNow("alice-pages", new SubmitJobRequest(
                List.of("pc-pages"), "yuni", "secret-prompt-alice", Channel.TEXT, OnBusy.queue));
        String session = initialize();

        String text = call(session, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"list_jobs\",\"arguments\":{}}}")
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode page = mapper.readTree(text);
        assertEquals(2, page.path("jobs").size());
        assertFalse(text.contains("secret-prompt"));
        assertFalse(text.contains(hidden.jobId().toString()));
        assertTrue(text.contains(mine.jobId().toString()));
        assertTrue(text.contains("finished-note"));
        assertTrue(text.contains("pages.local"));
        assertTrue(text.contains("\"done\""));
        assertTrue(text.contains("\"queued\""));

        String done = call(session, """
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"list_jobs","arguments":{"agentName":"yuni","state":"done","limit":1}}}
                """)
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        JsonNode donePage = mapper.readTree(done);
        assertEquals(1, donePage.path("jobs").size());
        assertEquals(mine.jobId().toString(), donePage.path("jobs").get(0).path("jobId").asText());
        assertFalse(done.contains(queued.jobId().toString()));
        assertFalse(done.contains("secret-prompt"));

        String got = call(session, getJob(mine.jobId().toString()))
                .statusCode(200)
                .body("result.isError", is(false))
                .extract()
                .path("result.content[0].text");
        assertTrue(got.contains("secret-prompt-mine"));

        call(session, """
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"list_jobs","arguments":{"state":"later"}}}
                """)
                .statusCode(200)
                .body("error.code", is(-32602));
    }

    @Test
    @TestSecurity(user = "grok-cap", roles = "dispatcher")
    void send_is_not_blocked_by_a_rest_submit_that_is_still_waiting() throws Exception {
        jobs.register("pc-cap-mcp", "mcp-cap.local", List.of("yuni"));
        String session = initialize();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> jobs.submit(
                    "grok-cap",
                    new SubmitJobRequest(List.of("pc-cap-mcp"), "yuni", "background", Channel.TEXT),
                    Duration.ofSeconds(5)));
            waitForQueued("pc-cap-mcp");
            int before = jobCount();

            String text = call(session, submitBody("mcp-cap.local", "second"))
                    .statusCode(200)
                    .body("result.isError", is(false))
                    .extract()
                    .path("result.content[0].text");

            JsonNode sent = mapper.readTree(text);
            assertEquals("QUEUED", sent.path("assignments").get(0).path("state").asText());
            assertFalse(text.contains("background"));
            assertEquals(before + 1, jobCount());
            for (AssignmentView row : jobs.poll("pc-cap-mcp", Duration.ZERO)) {
                jobs.finish("pc-cap-mcp", row.assignmentId(), true, "done");
            }
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
                .body("result.capabilities.tools", notNullValue())
                .body("result.capabilities.tools.listChanged", nullValue())
                .body("result.capabilities.resources", nullValue())
                .body("result.capabilities.prompts", nullValue())
                .body("result.serverInfo.name", is("freedriver-dispatcher"))
                .extract()
                .header(McpEndpoint.SESSION_HEADER);
    }

    private static ValidatableResponse call(String sessionId, String body) {
        return given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, sessionId)
                .body(body)
                .post("/mcp")
                .then();
    }

    private static String submitBody(String server, String prompt) {
        return """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"openbot_send_prompt","arguments":{"server":"%s","agentName":"yuni","prompt":"%s","channel":"TEXT"}}}
                """.formatted(server, prompt);
    }

    private static String awaitBody(String jobId, int delaySeconds) {
        return """
                {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"openbot_await_turn","arguments":{"jobId":"%s","delaySeconds":%d}}}
                """.formatted(jobId, delaySeconds);
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

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Inject
    AssignmentRepository assignments;

    /** Distinct jobs stored. A rejected call writes none. */
    private int jobCount() {
        return assignments.mongoCollection().distinct("jobId", String.class).into(new java.util.ArrayList<>()).size();
    }
}
