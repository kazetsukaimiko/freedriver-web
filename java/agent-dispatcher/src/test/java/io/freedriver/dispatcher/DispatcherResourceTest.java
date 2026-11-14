package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DispatcherResourceTest {

    @Inject
    JobStore jobs;

    @Test
    void anonymous_submit_is_rejected() {
        given()
                .contentType("application/json")
                .body("{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}")
                .post("/api/dispatcher/jobs")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "pc-1", roles = "device")
    void device_cannot_submit() {
        given()
                .contentType("application/json")
                .body("{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}")
                .post("/api/dispatcher/jobs")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "grok", roles = "dispatcher")
    void device_register_is_gone() {
        given()
                .contentType("application/json")
                .body("{\"hostname\":\"pc\",\"agents\":[\"yuni\"]}")
                .post("/api/device/register")
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "pc-http", roles = "device")
    void device_role_cannot_register_over_http() {
        given()
                .contentType("application/json")
                .body("{\"hostname\":\"pc-http\",\"agents\":[\"yuni\"]}")
                .post("/api/device/register")
                .then()
                .statusCode(404);
    }

    /**
     * StrictToolArguments applies to every JSON body the dispatcher reads. On main, Jackson coerced
     * these to a string or an enum ordinal and the job went through; now each is a 400 before the store.
     */
    @Test
    @TestSecurity(user = "grok-strict", roles = "dispatcher")
    void submit_refuses_a_value_of_the_wrong_json_type() {
        int before = jobs.listJobs("grok-strict").size();
        for (String body : new String[] {
                "{\"deviceIds\":[\"pc\"],\"agentName\":5,\"prompt\":\"hi\",\"channel\":\"TEXT\"}",
                "{\"deviceIds\":[5],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}",
                "{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":true,\"channel\":\"TEXT\"}",
                "{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":0}"}) {
            given()
                    .contentType("application/json")
                    .body(body)
                    .post("/api/dispatcher/jobs")
                    .then()
                    .statusCode(400);
        }
        org.junit.jupiter.api.Assertions.assertEquals(before, jobs.listJobs("grok-strict").size());
    }

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** The same bodies: Jackson's defaults (main) read each one; the service's mapper refuses each one. */
    @Test
    void the_service_mapper_refuses_what_default_jackson_coerced() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper defaults = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String body : new String[] {
                "{\"deviceIds\":[\"pc\"],\"agentName\":5,\"prompt\":\"hi\",\"channel\":\"TEXT\"}",
                "{\"deviceIds\":[5],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}",
                "{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":true,\"channel\":\"TEXT\"}",
                "{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":0}"}) {
            defaults.readValue(body, io.freedriver.dispatcher.model.SubmitJobRequest.class);
            org.junit.jupiter.api.Assertions.assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> mapper.readValue(body, io.freedriver.dispatcher.model.SubmitJobRequest.class), body);
        }
    }

    @Test
    @TestSecurity(user = "grok", roles = "dispatcher")
    void unknown_device_fails_immediately() {
        given()
                .contentType("application/json")
                .body("{\"deviceIds\":[\"missing-device\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}")
                .post("/api/dispatcher/jobs")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "grok", roles = "dispatcher")
    void another_dispatchers_job_is_not_found() {
        given()
                .get("/api/dispatcher/jobs/" + UUID.randomUUID())
                .then()
                .statusCode(404);
    }

    @Test
    void anonymous_list_is_rejected() {
        given().get("/api/dispatcher/jobs").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "pc-1", roles = "device")
    void device_cannot_list_jobs() {
        given().get("/api/dispatcher/jobs").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "grok-list", roles = "dispatcher")
    void list_returns_this_dispatchers_jobs_only() {
        jobs.register("pc-list", "list.local", List.of("yuni"));
        JobView own = jobs.submit(
                "grok-list",
                new SubmitJobRequest(List.of("pc-list"), "yuni", "list me", Channel.TEXT),
                Duration.ofMillis(20));
        jobs.submit(
                "other-list",
                new SubmitJobRequest(List.of("pc-list"), "yuni", "hidden", Channel.TEXT),
                Duration.ofMillis(20));

        given()
                .get("/api/dispatcher/jobs")
                .then()
                .statusCode(200)
                .body("jobId", hasItem(own.jobId().toString()))
                .body("prompt", hasItem("list me"))
                .body("prompt", not(hasItem("hidden")));

        given()
                .get("/api/dispatcher/jobs/" + own.jobId())
                .then()
                .statusCode(200)
                .body("jobId", is(own.jobId().toString()))
                .body("prompt", is("list me"));
    }

    @Test
    @TestSecurity(user = "grok-rest-cap", roles = "dispatcher")
    void second_rest_submit_conflicts_while_one_is_in_flight() throws Exception {
        jobs.register("pc-rest-cap", "cap.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> jobs.submit(
                    "grok-rest-cap",
                    new SubmitJobRequest(List.of("pc-rest-cap"), "yuni", "background", Channel.TEXT),
                    Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued("pc-rest-cap");

            given()
                    .contentType("application/json")
                    .body("""
                            {"deviceIds":["pc-rest-cap"],"agentName":"yuni","prompt":"second","channel":"TEXT"}
                            """)
                    .post("/api/dispatcher/jobs")
                    .then()
                    .statusCode(409)
                    .body("message", is("submit already in flight"));

            UUID grokSession = UUID.randomUUID();
            jobs.claim("pc-rest-cap", queued.assignmentId());
            jobs.update("pc-rest-cap", queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.RUNNING, grokSession, null));
            jobs.update("pc-rest-cap", queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, grokSession, null));
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @TestSecurity(user = "service-account-freedriver-portal-jobs", roles = "dispatcher-admin")
    void admin_jobs_lists_every_callers_assignments_newest_first_in_pages() throws Exception {
        jobs.register("pc-admin", "admin.local", List.of("yuni"));
        JobView older = jobs.submitNow("grok-admin-a",
                new SubmitJobRequest(List.of("pc-admin"), "yuni", "admin older", Channel.TEXT));
        Thread.sleep(5);
        JobView newer = jobs.submitNow("grok-admin-b",
                new SubmitJobRequest(List.of("pc-admin"), "yuni", "admin newer", Channel.TEXT));

        List<String> prompts = given().queryParam("limit", 200)
                .get("/api/dispatcher/admin/jobs")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("assignments.prompt", String.class);
        assertTrue(prompts.indexOf("admin newer") >= 0 && prompts.indexOf("admin newer") < prompts.indexOf("admin older"),
                prompts.toString());

        String next = given().queryParam("limit", 1)
                .get("/api/dispatcher/admin/jobs")
                .then()
                .statusCode(200)
                .body("assignments.size()", is(1))
                .body("assignments[0].assignmentId", is(newer.assignments().getFirst().assignmentId().toString()))
                .extract().path("next");
        given().queryParam("limit", 1).queryParam("cursor", next)
                .get("/api/dispatcher/admin/jobs")
                .then()
                .statusCode(200)
                .body("assignments[0].assignmentId", is(older.assignments().getFirst().assignmentId().toString()));
    }

    @Test
    @TestSecurity(user = "grok-admin", roles = "dispatcher")
    void the_dispatcher_role_cannot_list_every_callers_jobs() {
        given().get("/api/dispatcher/admin/jobs").then().statusCode(403);
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
}
