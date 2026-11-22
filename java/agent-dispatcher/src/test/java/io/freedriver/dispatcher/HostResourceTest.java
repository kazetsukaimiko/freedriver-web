package io.freedriver.dispatcher;

import io.freedriver.dispatcher.jobs.JobStore;
import io.freedriver.dispatcher.jobs.service.TakePastTheWindow;
import io.freedriver.dispatcher.store.Assignment;
import io.freedriver.dispatcher.store.AssignmentRepository;
import io.freedriver.dispatcher.store.DeviceRepository;
import io.freedriver.dispatcher.store.SubmitLocks;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.HostPaths;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.common.mapper.TypeRef;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The host API through HTTP: a device reports, takes, starts and finishes one job. */
@QuarkusTest
class HostResourceTest {

    private static final String REPORT = HostPaths.ROOT + HostPaths.REPORT;
    private static final String TAKE = HostPaths.ROOT + HostPaths.TAKE;

    @Inject
    JobStore jobs;

    @Inject
    AssignmentRepository assignments;

    @Inject
    DeviceRepository devices;

    @Inject
    SubmitLocks locks;

    @Test
    @TestSecurity(user = "pc-host-api", roles = "device")
    void a_device_reports_takes_starts_and_finishes_one_job() {
        given().contentType("application/json")
                .body("{\"hostname\":\"host-api.local\",\"bots\":[{\"name\":\"yuni\",\"state\":\"idle\",\"activity\":\"waiting\"}]}")
                .post(REPORT)
                .then()
                .statusCode(200)
                .body("deviceId", is("pc-host-api"))
                .body("hostname", is("host-api.local"));
        String assignment = jobs.submitNow("grok-host-api", new SubmitJobRequest(
                List.of("pc-host-api"), "yuni", "host-api-prompt", Channel.TEXT))
                .assignments().getFirst().assignmentId().toString();

        given().contentType("application/json")
                .body("{\"waitSeconds\":0}")
                .post(TAKE)
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].assignmentId", is(assignment))
                .body("[0].state", is("CLAIMED"))
                .body("[0].deliveryRef", nullValue());
        given().contentType("application/json")
                .body("{\"waitSeconds\":0,\"holding\":[\"" + assignment + "\"]}")
                .post(TAKE)
                .then()
                .statusCode(200)
                .body("", hasSize(0));

        given().contentType("application/json")
                .body("{\"deliveryRef\":\"openbot:task-1\"}")
                .post(path(HostPaths.START, assignment))
                .then()
                .statusCode(200)
                .body("state", is("RUNNING"))
                .body("deliveryRef", is("openbot:task-1"));
        given().contentType("application/json")
                .body("{\"deliveryRef\":\"openbot:task-1\"}")
                .post(path(HostPaths.START, assignment))
                .then()
                .statusCode(200)
                .body("deliveryRef", is("openbot:task-1"));
        given().contentType("application/json")
                .body("{\"deliveryRef\":\"openbot:task-2\"}")
                .post(path(HostPaths.START, assignment))
                .then()
                .statusCode(409);

        given().contentType("application/json")
                .body("{\"succeeded\":true,\"note\":\"done\"}")
                .post(path(HostPaths.FINISH, assignment))
                .then()
                .statusCode(200)
                .body("state", is("SUCCEEDED"))
                .body("deliveryRef", is("openbot:task-1"));
    }

    @Test
    @TestSecurity(user = "pc-host-bad", roles = "device")
    void bad_bodies_are_400_and_change_nothing() {
        jobs.register("pc-host-bad", "bad.local", List.of("yuni"));
        String assignment = jobs.submitNow("grok-host-bad", new SubmitJobRequest(
                List.of("pc-host-bad"), "yuni", "host-bad-prompt", Channel.TEXT))
                .assignments().getFirst().assignmentId().toString();

        given().contentType("application/json").body("{\"waitSeconds\":51}").post(TAKE)
                .then().statusCode(400);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            ids.add("\"" + UUID.randomUUID() + "\"");
        }
        given().contentType("application/json")
                .body("{\"waitSeconds\":0,\"holding\":[" + String.join(",", ids) + "]}")
                .post(TAKE)
                .then().statusCode(400);
        for (String bad : List.of("has space", "", "r".repeat(129))) {
            given().contentType("application/json")
                    .body("{\"deliveryRef\":\"" + bad + "\"}")
                    .post(path(HostPaths.START, assignment))
                    .then().statusCode(400);
        }
        assertEquals("QUEUED", jobs.assignment("pc-host-bad", UUID.fromString(assignment)).state().name());
    }

    @Test
    @TestSecurity(user = "pc-host-other", roles = "device")
    void another_devices_assignment_is_403_and_an_unknown_one_404() {
        jobs.register("pc-host-owner", "owner.local", List.of("yuni"));
        String theirs = jobs.submitNow("grok-host-other", new SubmitJobRequest(
                List.of("pc-host-owner"), "yuni", "not-yours", Channel.TEXT))
                .assignments().getFirst().assignmentId().toString();

        given().contentType("application/json").body("{\"deliveryRef\":\"ref\"}")
                .post(path(HostPaths.START, theirs)).then().statusCode(403);
        given().contentType("application/json").body("{\"succeeded\":true}")
                .post(path(HostPaths.FINISH, theirs)).then().statusCode(403);
        given().contentType("application/json").body("{\"deliveryRef\":\"ref\"}")
                .post(path(HostPaths.START, UUID.randomUUID().toString())).then().statusCode(404);
        assertFinishError(UUID.randomUUID().toString(), 404, "assignment not found");

        String succeededPrompt = "prompt-succeeded-violet-quartz";
        String succeededNote = "note-succeeded-violet-quartz";
        UUID succeeded = ownedFinished(true, succeededPrompt, succeededNote);
        assertFinishError(succeeded.toString(), 403, "assignment belongs to another device",
                succeededPrompt, succeededNote);

        String failedPrompt = "prompt-failed-amber-lattice";
        String failedNote = "note-failed-amber-lattice";
        UUID failed = ownedFinished(false, failedPrompt, failedNote);
        assertFinishError(failed.toString(), 403, "assignment belongs to another device",
                failedPrompt, failedNote);

        String expiredPrompt = "prompt-expired-cobalt-harbor";
        String expiredNote = "note-expired-cobalt-harbor";
        UUID expired = jobs.submitNow("grok-host-other", new SubmitJobRequest(
                List.of("pc-host-owner"), "yuni", expiredPrompt, Channel.TEXT))
                .assignments().getFirst().assignmentId();
        jobs.update("pc-host-owner", expired, new UpdateAssignmentRequest(AssignmentState.EXPIRED, null, expiredNote));
        assertFinishError(expired.toString(), 403, "assignment belongs to another device",
                expiredPrompt, expiredNote);
    }

    @Test
    @TestSecurity(user = "grok-host-denied", roles = "dispatcher")
    void a_dispatcher_token_is_403_on_every_route() {
        String any = UUID.randomUUID().toString();
        given().contentType("application/json")
                .body("{\"hostname\":\"x.local\",\"bots\":[{\"name\":\"yuni\",\"state\":\"idle\"}]}")
                .post(REPORT).then().statusCode(403);
        given().contentType("application/json").body("{\"waitSeconds\":0}")
                .post(TAKE).then().statusCode(403);
        given().contentType("application/json").body("{\"deliveryRef\":\"ref\"}")
                .post(path(HostPaths.START, any)).then().statusCode(403);
        given().contentType("application/json").body("{\"succeeded\":true}")
                .post(path(HostPaths.FINISH, any)).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "pc-host-kept", roles = "device")
    void finish_on_a_finished_job_answers_the_stored_job_and_changes_nothing() {
        jobs.register("pc-host-kept", "kept.local", List.of("yuni"));
        UUID done = jobs.submitNow("grok-host-kept", new SubmitJobRequest(
                List.of("pc-host-kept"), "yuni", "host-kept-prompt", Channel.TEXT))
                .assignments().getFirst().assignmentId();
        jobs.take("pc-host-kept", List.of(), Duration.ZERO);
        jobs.start("pc-host-kept", done, "openbot:task-kept");
        jobs.finish("pc-host-kept", done, true, "the agent's result");
        AssignmentView agents = jobs.assignment("pc-host-kept", done);
        assertEquals(AssignmentState.SUCCEEDED, agents.state());
        assertEquals("the agent's result", agents.note());
        assertEquals("openbot:task-kept", agents.deliveryRef());

        finishAgainLeavesTheStoredRow(agents, false, "the turn ended without a result");
        finishAgainLeavesTheStoredRow(agents, true, "a later finish that claims success");

        UUID failed = jobs.submitNow("grok-host-kept", new SubmitJobRequest(
                List.of("pc-host-kept"), "yuni", "host-kept-prompt-2", Channel.TEXT))
                .assignments().getFirst().assignmentId();
        AssignmentView first = hostFinish(failed, false, "first");
        assertEquals(AssignmentState.FAILED, first.state());
        assertEquals("first", first.note());
        finishAgainLeavesTheStoredRow(first, true, "second");

        UUID expired = jobs.submitNow("grok-host-kept", new SubmitJobRequest(
                List.of("pc-host-kept"), "yuni", "host-kept-prompt-3", Channel.TEXT))
                .assignments().getFirst().assignmentId();
        jobs.update("pc-host-kept", expired, new UpdateAssignmentRequest(AssignmentState.EXPIRED, null, null));
        AssignmentView lapsed = jobs.assignment("pc-host-kept", expired);
        assertEquals(AssignmentState.EXPIRED, lapsed.state());
        assertNull(lapsed.note());
        finishAgainLeavesTheStoredRow(lapsed, true, "too late");
    }

    @Test
    @TestSecurity(user = "pc-host-deadline", roles = "device")
    void finish_on_a_job_expired_at_submit_answers_the_stored_job_and_changes_nothing() {
        jobs.register("pc-host-deadline", "deadline.local", List.of("yuni"));
        UUID expired = jobs.submit("grok-host-deadline", new SubmitJobRequest(
                List.of("pc-host-deadline"), "yuni", "host-deadline-prompt", Channel.TEXT),
                Duration.ofMillis(40))
                .assignments().getFirst().assignmentId();
        AssignmentView stored = jobs.assignment("pc-host-deadline", expired);
        assertEquals(AssignmentState.EXPIRED, stored.state());
        assertNull(stored.note());
        finishAgainLeavesTheStoredRow(stored, false, "a finish after the deadline");
    }

    @Test
    @TestSecurity(user = "pc-host-untaken", roles = "device")
    void finish_on_a_job_no_host_took_answers_the_stored_job_and_changes_nothing() {
        String deviceId = "pc-host-untaken";
        jobs.register(deviceId, "untaken.local", List.of("yuni"));
        UUID expired = jobs.submitNow("grok-host-untaken", new SubmitJobRequest(
                List.of(deviceId), "yuni", "host-untaken-prompt", Channel.TEXT))
                .assignments().getFirst().assignmentId();
        // createdAt is the CDI clock. Take's cutoff is that same clock shifted past take-within.
        TakePastTheWindow.take(assignments, devices, locks, deviceId);
        Assignment row = assignments.findById(expired.toString());
        assertEquals(AssignmentState.EXPIRED, row.status);
        assertEquals("no host took it", row.note);
        AssignmentView stored = jobs.assignment(deviceId, expired);
        assertEquals(AssignmentState.EXPIRED, stored.state());
        assertEquals("no host took it", stored.note());
        finishAgainLeavesTheStoredRow(stored, false, "the turn ended without a result");
    }

    /** Replay finish, compare the view, and require the stored row's status, note, updatedAt and heldUntil to match. */
    private void finishAgainLeavesTheStoredRow(AssignmentView stored, boolean succeeded, String note) {
        assertNotEquals(stored.note(), note);
        String id = stored.assignmentId().toString();
        Assignment before = assignments.findById(id);
        AssignmentView answered = hostFinish(stored.assignmentId(), succeeded, note);
        assertEquals(stored, answered);
        assertEquals(stored, jobs.assignment(stored.deviceId(), stored.assignmentId()));
        Assignment after = assignments.findById(id);
        assertEquals(before.status, after.status);
        assertEquals(before.note, after.note);
        assertEquals(before.updatedAt, after.updatedAt);
        assertEquals(before.heldUntil, after.heldUntil);
    }

    private UUID ownedFinished(boolean succeeded, String prompt, String note) {
        UUID assignment = jobs.submitNow("grok-host-other", new SubmitJobRequest(
                List.of("pc-host-owner"), "yuni", prompt, Channel.TEXT))
                .assignments().getFirst().assignmentId();
        AssignmentView finished = jobs.finish("pc-host-owner", assignment, succeeded, note);
        assertEquals(succeeded ? AssignmentState.SUCCEEDED : AssignmentState.FAILED, finished.state());
        assertEquals(note, finished.note());
        assertEquals(prompt, finished.prompt());
        return assignment;
    }

    private static void assertFinishError(String assignmentId, int status, String message, String... absent) {
        Response response = given().contentType("application/json")
                .body("{\"succeeded\":true,\"note\":\"replay-note\"}")
                .post(path(HostPaths.FINISH, assignmentId))
                .then()
                .statusCode(status)
                .extract()
                .response();
        String raw = response.asString();
        Map<String, Object> body = response.as(new TypeRef<>() { });
        assertEquals(Set.of("message"), body.keySet());
        assertEquals(message, body.get("message"));
        for (String secret : absent) {
            assertFalse(raw.contains(secret), raw);
        }
    }

    private static AssignmentView hostFinish(UUID assignment, boolean succeeded, String note) {
        return given().contentType("application/json")
                .body("{\"succeeded\":" + succeeded + ",\"note\":\"" + note + "\"}")
                .post(path(HostPaths.FINISH, assignment.toString()))
                .then()
                .statusCode(200)
                .extract()
                .as(AssignmentView.class);
    }

    static String path(String route, String assignmentId) {
        return HostPaths.ROOT + route.replace("{" + HostPaths.ASSIGNMENT_ID + "}", assignmentId);
    }
}
