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
    void dispatcher_cannot_register() {
        given()
                .contentType("application/json")
                .body("{\"hostname\":\"pc\",\"agents\":[\"yuni\"]}")
                .post("/api/device/register")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "pc-http", roles = "device")
    void register_uses_the_caller_name_as_device_id() {
        given()
                .contentType("application/json")
                .body("{\"hostname\":\"pc-http\",\"agents\":[\"yuni\"]}")
                .post("/api/device/register")
                .then()
                .statusCode(200)
                .body("deviceId", is("pc-http"))
                .body("agents", hasItem("yuni"));
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
