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
    void other_device_queue_stays_empty() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued(store, "pc-a");

            assertEquals(AssignmentState.QUEUED, queued.state());
            assertTrue(store.poll("pc-b", Duration.ZERO).isEmpty());
            succeed(store, queued);
            assertEquals(AssignmentState.SUCCEEDED, pending.get(5, TimeUnit.SECONDS).assignments().get(0).state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void device_cannot_claim_another_devices_assignment() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued(store, "pc-a");

            DispatcherException error = assertThrows(
                    DispatcherException.class, () -> store.claim("pc-b", queued.assignmentId()));
            assertEquals(DispatcherException.Kind.FORBIDDEN, error.kind());
            assertEquals(AssignmentState.QUEUED, store.poll("pc-a", Duration.ZERO).get(0).state());
            succeed(store, queued);
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
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
    void submit_expires_when_nobody_claims() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));

        JobView submitted = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ofMillis(40));

        assertEquals(AssignmentState.EXPIRED, submitted.assignments().get(0).state());
        assertTrue(store.poll("pc-a", Duration.ZERO).isEmpty());
        DispatcherException error = assertThrows(DispatcherException.class,
                () -> store.claim("pc-a", submitted.assignments().get(0).assignmentId()));
        assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
        assertEquals(submitted, store.getJob("grok", submitted.jobId()));
        assertThrows(DispatcherException.class, () -> store.getJob("someone-else", submitted.jobId()));
    }

    @Test
    void claimed_assignment_expires_at_the_deadline() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(2)));
            AssignmentView queued = waitForQueued(store, "pc-a");
            store.claim("pc-a", queued.assignmentId());

            JobView finished = pending.get(5, TimeUnit.SECONDS);
            assertEquals(AssignmentState.EXPIRED, finished.assignments().get(0).state());
            DispatcherException error = assertThrows(DispatcherException.class, () -> store.update(
                    "pc-a",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.RUNNING, UUID.randomUUID(), null)));
            assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deadline_leaves_a_finished_assignment_and_expires_the_other() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a", "pc-b"), "yuni"), Duration.ofSeconds(2)));
            AssignmentView queued = waitForQueued(store, "pc-a");
            succeed(store, queued);

            JobView finished = pending.get(5, TimeUnit.SECONDS);
            assertEquals(AssignmentState.SUCCEEDED, assignment(finished, "pc-a").state());
            assertEquals(AssignmentState.EXPIRED, assignment(finished, "pc-b").state());
            DispatcherException error = assertThrows(DispatcherException.class,
                    () -> store.claim("pc-b", assignment(finished, "pc-b").assignmentId()));
            assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void one_in_flight_submit_per_subject() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JobView> first = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            waitForQueued(store, "pc-a");
            Future<JobView> other = pool.submit(() -> store.submit(
                    "other", job(List.of("pc-b"), "yuni"), Duration.ofSeconds(5)));
            waitForQueued(store, "pc-b");

            DispatcherException error = assertThrows(DispatcherException.class, () -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
            assertEquals("submit already in flight", error.getMessage());

            succeed(store, store.poll("pc-a", Duration.ZERO).get(0));
            succeed(store, store.poll("pc-b", Duration.ZERO).get(0));
            first.get(5, TimeUnit.SECONDS);
            other.get(5, TimeUnit.SECONDS);

            JobView again = store.submit("grok", job(List.of("pc-a"), "yuni"), Duration.ofMillis(40));
            assertEquals(AssignmentState.EXPIRED, again.assignments().get(0).state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void illegal_jump_to_succeeded_conflicts() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued(store, "pc-a");

            DispatcherException error = assertThrows(DispatcherException.class, () -> store.update(
                    "pc-a",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, null)));
            assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
            assertEquals(AssignmentState.QUEUED, store.poll("pc-a", Duration.ZERO).get(0).state());
            succeed(store, queued);
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
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

    private static void succeed(JobStore store, AssignmentView queued) {
        UUID session = UUID.randomUUID();
        store.claim(queued.deviceId(), queued.assignmentId());
        store.update(queued.deviceId(), queued.assignmentId(),
                new UpdateAssignmentRequest(AssignmentState.RUNNING, session, null));
        store.update(queued.deviceId(), queued.assignmentId(),
                new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, session, null));
    }

    private static AssignmentView assignment(JobView job, String deviceId) {
        for (AssignmentView assignment : job.assignments()) {
            if (deviceId.equals(assignment.deviceId())) {
                return assignment;
            }
        }
        throw new AssertionError(deviceId);
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
