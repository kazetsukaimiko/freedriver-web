package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStoreTest {

    @Test
    void unknown_agent_fails_before_queueing() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));

        DispatcherException error = assertThrows(DispatcherException.class, () -> store.submit(
                "grok",
                job(List.of("pc-a"), "missing"),
                Duration.ZERO));

        assertEquals(DispatcherException.Kind.BAD_REQUEST, error.kind());
        assertTrue(store.poll("pc-a", Duration.ZERO).isEmpty());
    }

    @Test
    void other_device_queue_stays_empty() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));

        JobView submitted = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ZERO);

        assertEquals(AssignmentState.QUEUED, submitted.assignments().get(0).state());
        assertTrue(store.poll("pc-b", Duration.ZERO).isEmpty());
        assertEquals(1, store.poll("pc-a", Duration.ZERO).size());
    }

    @Test
    void device_cannot_claim_another_devices_assignment() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        UUID assignmentId = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ZERO)
                .assignments().get(0).assignmentId();

        DispatcherException error = assertThrows(DispatcherException.class, () -> store.claim("pc-b", assignmentId));
        assertEquals(DispatcherException.Kind.FORBIDDEN, error.kind());
        assertEquals(AssignmentState.QUEUED, store.poll("pc-a", Duration.ZERO).get(0).state());
    }

    @Test
    void claim_run_succeed_wakes_submit() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued(store, "pc-a");
            store.claim("pc-a", queued.assignmentId());
            UUID session = UUID.randomUUID();
            store.update("pc-a", queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.RUNNING, session, null));
            store.update("pc-a", queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, session, "done"));

            JobView finished = pending.get(5, TimeUnit.SECONDS);
            assertEquals(AssignmentState.SUCCEEDED, finished.assignments().get(0).state());
            assertEquals(session, finished.assignments().get(0).grokSessionId());
            assertEquals(finished, store.getJob("grok", finished.jobId()));
            assertThrows(DispatcherException.class, () -> store.getJob("someone-else", finished.jobId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void submit_returns_queued_when_nobody_claims() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));

        JobView submitted = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ofMillis(40));

        assertEquals(AssignmentState.QUEUED, submitted.assignments().get(0).state());
    }

    @Test
    void prompt_is_only_on_the_owning_devices_assignment() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        String shared = "shared-prompt-for-both";
        JobView both = store.submit(
                "grok",
                new SubmitJobRequest(List.of("pc-a", "pc-b"), "yuni", shared, Channel.TEXT),
                Duration.ZERO);

        assertEquals(shared, both.prompt());
        assertEquals(shared, both.assignments().get(0).prompt());
        assertEquals(Channel.TEXT, both.assignments().get(0).channel());

        AssignmentView onA = store.poll("pc-a", Duration.ZERO).stream()
                .filter(view -> view.jobId().equals(both.jobId()))
                .findFirst()
                .orElseThrow();
        AssignmentView onB = store.poll("pc-b", Duration.ZERO).stream()
                .filter(view -> view.jobId().equals(both.jobId()))
                .findFirst()
                .orElseThrow();
        assertEquals(shared, onA.prompt());
        assertEquals(onA.deviceId(), "pc-a");
        assertFalse(onA.assignmentId().equals(onB.assignmentId()));

        String secretPrompt = "do-not-show-pc-b-this";
        JobView onlyA = store.submit(
                "grok",
                new SubmitJobRequest(List.of("pc-a"), "yuni", secretPrompt, Channel.SPEECH),
                Duration.ZERO);
        assertTrue(store.poll("pc-b", Duration.ZERO).stream()
                .noneMatch(view -> secretPrompt.equals(view.prompt())));
        assertEquals(secretPrompt, store.assignment("pc-a", onlyA.assignments().get(0).assignmentId()).prompt());
        assertEquals(Channel.SPEECH, store.assignment("pc-a", onlyA.assignments().get(0).assignmentId()).channel());

        DispatcherException denied = assertThrows(
                DispatcherException.class,
                () -> store.assignment("pc-b", onlyA.assignments().get(0).assignmentId()));
        assertEquals(DispatcherException.Kind.FORBIDDEN, denied.kind());
        assertFalse(denied.getMessage().contains(secretPrompt));

        DispatcherException otherDispatcher = assertThrows(
                DispatcherException.class,
                () -> store.getJob("someone-else", onlyA.jobId()));
        assertFalse(otherDispatcher.getMessage().contains(secretPrompt));
    }

    @Test
    void claimed_can_fail_without_a_session() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        UUID assignmentId = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ZERO)
                .assignments().get(0).assignmentId();
        store.claim("pc-a", assignmentId);

        AssignmentView failed = store.update(
                "pc-a",
                assignmentId,
                new UpdateAssignmentRequest(AssignmentState.FAILED, null, "refused SPEECH"));

        assertEquals(AssignmentState.FAILED, failed.state());
        assertNull(failed.grokSessionId());
        assertEquals("refused SPEECH", failed.note());
        DispatcherException succeeded = assertThrows(DispatcherException.class, () -> store.update(
                "pc-a",
                assignmentId,
                new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, null)));
        assertEquals(DispatcherException.Kind.CONFLICT, succeeded.kind());
    }

    @Test
    void succeeded_from_claimed_conflicts() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        UUID assignmentId = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ZERO)
                .assignments().get(0).assignmentId();
        store.claim("pc-a", assignmentId);

        DispatcherException error = assertThrows(DispatcherException.class, () -> store.update(
                "pc-a",
                assignmentId,
                new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, null)));
        assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
    }

    @Test
    void illegal_jump_to_succeeded_conflicts() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        UUID assignmentId = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ZERO)
                .assignments().get(0).assignmentId();

        DispatcherException error = assertThrows(DispatcherException.class, () -> store.update(
                "pc-a",
                assignmentId,
                new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, null)));
        assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
        DispatcherException failed = assertThrows(DispatcherException.class, () -> store.update(
                "pc-a",
                assignmentId,
                new UpdateAssignmentRequest(AssignmentState.FAILED, null, null)));
        assertEquals(DispatcherException.Kind.CONFLICT, failed.kind());
    }

    @Test
    void heartbeat_advances_and_unknown_device_is_missing() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
        JobStore store = new JobStore(clock);
        store.register("pc-a", "a.local", List.of("yuni"));
        clock.now = clock.now.plusSeconds(30);

        assertEquals(clock.now, store.heartbeat("pc-a").lastHeartbeat());
        DispatcherException error = assertThrows(DispatcherException.class, () -> store.heartbeat("missing"));
        assertEquals(DispatcherException.Kind.NOT_FOUND, error.kind());
    }

    private static AssignmentView waitForQueued(JobStore store, String deviceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<AssignmentView> queued = store.poll(deviceId, Duration.ZERO);
            if (!queued.isEmpty()) {
                return queued.get(0);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("assignment was not queued");
    }

    private static JobStore store() {
        return new JobStore(Clock.systemUTC());
    }

    private static SubmitJobRequest job(List<String> deviceIds, String agent) {
        return new SubmitJobRequest(deviceIds, agent, "say hello", Channel.TEXT);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
