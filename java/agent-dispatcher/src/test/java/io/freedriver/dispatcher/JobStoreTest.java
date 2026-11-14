package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.JobPage;
import io.freedriver.dispatcher.model.JobSummary;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production store on the Dev Services Mongo container. Each test starts from empty collections. */
@QuarkusTest
class JobStoreTest {

    @Inject
    AssignmentRepository assignments;

    @Inject
    DeviceRepository devices;

    @Inject
    SubmitLocks locks;

    @BeforeEach
    void empty() {
        assignments.deleteAll();
        devices.deleteAll();
        locks.deleteAll();
    }

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
        assertEquals(List.of(submitted), store.listJobs("grok"));
        assertEquals(List.of(), store.listJobs("someone-else"));
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

            DispatcherException succeeded = assertThrows(DispatcherException.class, () -> store.update(
                    "pc-a",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, null)));
            assertEquals(DispatcherException.Kind.CONFLICT, succeeded.kind());
            DispatcherException failed = assertThrows(DispatcherException.class, () -> store.update(
                    "pc-a",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.FAILED, null, null)));
            assertEquals(DispatcherException.Kind.CONFLICT, failed.kind());
            assertEquals(AssignmentState.QUEUED, store.poll("pc-a", Duration.ZERO).get(0).state());
            succeed(store, queued);
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void prompt_is_only_on_the_owning_devices_assignment() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String shared = "shared-prompt-for-both";
            Future<JobView> bothPending = pool.submit(() -> store.submit(
                    "grok",
                    new SubmitJobRequest(List.of("pc-a", "pc-b"), "yuni", shared, Channel.TEXT),
                    Duration.ofSeconds(5)));
            AssignmentView onA = waitForQueued(store, "pc-a");
            AssignmentView onB = waitForQueued(store, "pc-b");
            assertEquals(shared, onA.prompt());
            assertEquals(Channel.TEXT, onA.channel());
            assertEquals("pc-a", onA.deviceId());
            assertEquals(shared, onB.prompt());
            assertFalse(onA.assignmentId().equals(onB.assignmentId()));
            succeed(store, onA);
            succeed(store, onB);
            JobView both = bothPending.get(5, TimeUnit.SECONDS);
            assertEquals(shared, both.prompt());
            assertEquals(shared, assignment(both, "pc-a").prompt());
            assertEquals(Channel.TEXT, assignment(both, "pc-a").channel());

            String secretPrompt = "do-not-show-pc-b-this";
            Future<JobView> onlyAPending = pool.submit(() -> store.submit(
                    "grok",
                    new SubmitJobRequest(List.of("pc-a"), "yuni", secretPrompt, Channel.SPEECH),
                    Duration.ofSeconds(5)));
            AssignmentView secret = waitForQueued(store, "pc-a");
            assertTrue(store.poll("pc-b", Duration.ZERO).stream()
                    .noneMatch(view -> secretPrompt.equals(view.prompt())));
            assertEquals(secretPrompt, store.assignment("pc-a", secret.assignmentId()).prompt());
            assertEquals(Channel.SPEECH, store.assignment("pc-a", secret.assignmentId()).channel());

            DispatcherException denied = assertThrows(
                    DispatcherException.class,
                    () -> store.assignment("pc-b", secret.assignmentId()));
            assertEquals(DispatcherException.Kind.FORBIDDEN, denied.kind());
            assertFalse(denied.getMessage().contains(secretPrompt));

            succeed(store, secret);
            JobView onlyA = onlyAPending.get(5, TimeUnit.SECONDS);
            DispatcherException otherDispatcher = assertThrows(
                    DispatcherException.class,
                    () -> store.getJob("someone-else", onlyA.jobId()));
            assertFalse(otherDispatcher.getMessage().contains(secretPrompt));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void openbot_worker_runs_without_a_grok_session() throws Exception {
        JobStore store = store();
        store.register("freedriver-openbot", "luna", List.of("techops", "yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok.com",
                    new SubmitJobRequest(List.of("freedriver-openbot"), "techops", "check appliance states", Channel.TEXT),
                    Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued(store, "freedriver-openbot");
            store.claim("freedriver-openbot", queued.assignmentId());
            AssignmentView running = store.update(
                    "freedriver-openbot",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.RUNNING, null, null));
            assertEquals(AssignmentState.RUNNING, running.state());
            assertNull(running.grokSessionId());
            assertEquals("techops", running.agentName());
            store.update(
                    "freedriver-openbot",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, "pumps are on"));

            JobView finished = pending.get(5, TimeUnit.SECONDS);
            assertEquals(AssignmentState.SUCCEEDED, finished.assignments().get(0).state());
            assertNull(finished.assignments().get(0).grokSessionId());
            assertEquals("pumps are on", finished.assignments().get(0).note());
            assertEquals("techops", finished.assignments().get(0).agentName());
            assertEquals("freedriver-openbot", finished.assignments().get(0).deviceId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void claimed_can_fail_without_a_session() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued(store, "pc-a");
            store.claim("pc-a", queued.assignmentId());

            AssignmentView failed = store.update(
                    "pc-a",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.FAILED, null, "refused SPEECH"));

            assertEquals(AssignmentState.FAILED, failed.state());
            assertNull(failed.grokSessionId());
            assertEquals("refused SPEECH", failed.note());
            DispatcherException succeeded = assertThrows(DispatcherException.class, () -> store.update(
                    "pc-a",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, null)));
            assertEquals(DispatcherException.Kind.CONFLICT, succeeded.kind());
            assertEquals(AssignmentState.FAILED, pending.get(5, TimeUnit.SECONDS).assignments().get(0).state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void succeeded_from_claimed_conflicts() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            AssignmentView queued = waitForQueued(store, "pc-a");
            store.claim("pc-a", queued.assignmentId());

            DispatcherException error = assertThrows(DispatcherException.class, () -> store.update(
                    "pc-a",
                    queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.SUCCEEDED, null, null)));
            assertEquals(DispatcherException.Kind.CONFLICT, error.kind());
            store.update("pc-a", queued.assignmentId(),
                    new UpdateAssignmentRequest(AssignmentState.FAILED, null, "refused"));
            assertEquals(AssignmentState.FAILED, pending.get(5, TimeUnit.SECONDS).assignments().get(0).state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void heartbeat_advances_and_unknown_device_is_missing() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
        JobStore store = service(clock, null);
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

    @Test
    void submit_now_leaves_the_job_queued_so_a_later_claim_works() {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));

        JobView submitted = store.submitNow("grok", job(List.of("pc-a"), "yuni"));

        assertEquals(AssignmentState.QUEUED, submitted.assignments().get(0).state());
        assertEquals(submitted, store.getJob("grok", submitted.jobId()));
        AssignmentView claimed = store.claim("pc-a", submitted.assignments().get(0).assignmentId());
        assertEquals(AssignmentState.CLAIMED, claimed.state());
        store.finish("pc-a", claimed.assignmentId(), true, "done");
        assertEquals(AssignmentState.SUCCEEDED, store.getJob("grok", submitted.jobId()).assignments().get(0).state());
    }

    @Test
    void a_restart_keeps_the_queued_job_for_a_later_claim() {
        JobStore first = store();
        first.register("pc-a", "a.local", List.of("yuni"));
        JobView submitted = first.submitNow("grok", job(List.of("pc-a"), "yuni"));

        List<Document> rows = storedJobs();
        assertEquals(1, rows.size());
        assertEquals("QUEUED", rows.get(0).getString("status"));
        assertEquals("TEXT", rows.get(0).getString("channel"));
        assertTrue(rows.get(0).getString("prompt") != null && !rows.get(0).getString("prompt").isBlank());
        assertTrue(rows.get(0).get("createdAt") != null);

        JobStore restarted = store();
        AssignmentView claimed = restarted.claim("pc-a", submitted.assignments().get(0).assignmentId());
        assertEquals(AssignmentState.CLAIMED, claimed.state());
        assertEquals("CLAIMED", storedJobs().get(0).getString("status"));
        assertEquals(submitted.jobId(), restarted.getJob("grok", submitted.jobId()).jobId());
    }

    @Test
    void a_wait_inside_the_cap_returns_the_finished_job() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> pending = pool.submit(() -> store.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(30)));
            AssignmentView queued = waitForQueued(store, "pc-a");
            store.finish("pc-a", queued.assignmentId(), true, "done");
            JobView finished = pending.get(5, TimeUnit.SECONDS);
            assertEquals(AssignmentState.SUCCEEDED, finished.assignments().get(0).state());
            assertEquals("done", store.getJob("grok", finished.jobId()).assignments().get(0).note());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void queued_job_pings_each_host_and_not_the_prompt() {
        List<String> hosts = new ArrayList<>();
        JobStore store = service(Clock.systemUTC(), hosts::add);
        store.register("pc-a", "a.local", List.of("yuni"));
        store.register("pc-b", "b.local", List.of("yuni"));
        store.register("pc-c", "a.local", List.of("yuni"));

        store.submit(
                "grok",
                new SubmitJobRequest(List.of("pc-a", "pc-b", "pc-c"), "yuni", "secret prompt", Channel.TEXT),
                Duration.ZERO);

        assertEquals(List.of("a.local", "b.local"), hosts);
        assertFalse(hosts.contains("secret prompt"));
    }

    @Test
    void rejected_submit_does_not_ping() {
        List<String> hosts = new ArrayList<>();
        JobStore store = service(Clock.systemUTC(), hosts::add);
        store.register("pc-a", "a.local", List.of("yuni"));

        assertThrows(DispatcherException.class, () -> store.submit(
                "grok",
                job(List.of("pc-a"), "missing"),
                Duration.ZERO));

        assertTrue(hosts.isEmpty());
    }

    @Test
    void send_writes_the_row_before_the_webhook_returns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobStore store = service(Clock.systemUTC(), hostname -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("webhook was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        store.register("pc-a", "a.local", List.of("yuni"));

        JobView sent = store.submitNow(
                "grok", new SubmitJobRequest(List.of("pc-a"), "yuni", "prompt", Channel.TEXT));

        assertEquals(1, storedJobs().size());
        assertEquals(sent.jobId().toString(), storedJobs().get(0).getString("jobId"));
        assertEquals("QUEUED", storedJobs().get(0).getString("status"));
        assertEquals("supersede", storedJobs().get(0).getString("on_busy"));
        assertEquals(OnBusy.supersede, store.poll("pc-a", Duration.ZERO).get(0).onBusy());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        release.countDown();
    }

    @Test
    void queue_is_stored_before_the_webhook_returns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobStore store = service(Clock.systemUTC(), hostname -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("webhook was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        store.register("pc-a", "a.local", List.of("yuni"));

        store.submitNow("grok", new SubmitJobRequest(
                List.of("pc-a"), "yuni", "prompt", Channel.TEXT, OnBusy.queue));

        assertEquals(1, storedJobs().size());
        assertEquals("queue", storedJobs().get(0).getString("on_busy"));
        assertEquals(OnBusy.queue, store.poll("pc-a", Duration.ZERO).get(0).onBusy());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        release.countDown();
    }

    @Test
    void await_returns_the_finished_job_and_a_timeout_leaves_it_queued() throws Exception {
        JobStore store = store();
        store.register("pc-a", "a.local", List.of("yuni"));
        JobView sent = store.submitNow("grok", job(List.of("pc-a"), "yuni"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobView> waiting = pool.submit(() -> store.awaitTurn("grok", sent.jobId(), Duration.ofSeconds(30)));
            AssignmentView queued = waitForQueued(store, "pc-a");
            store.finish("pc-a", queued.assignmentId(), true, "done");
            assertEquals(AssignmentState.SUCCEEDED, waiting.get(5, TimeUnit.SECONDS).assignments().get(0).state());
        } finally {
            pool.shutdownNow();
        }

        JobView second = store.submitNow("grok", job(List.of("pc-a"), "yuni"));
        JobView timedOut = store.awaitTurn("grok", second.jobId(), Duration.ofSeconds(1));
        assertEquals(AssignmentState.QUEUED, timedOut.assignments().get(0).state());
        assertEquals(2, jobCount());
    }

    @Test
    void a_wait_or_list_written_on_one_process_is_visible_on_the_other() throws Exception {
        JobStore first = store();
        JobStore second = store();
        first.register("pc-a", "a.local", List.of("yuni"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<AssignmentView>> waiting = pool.submit(
                    () -> second.poll("pc-a", Duration.ofSeconds(5)));
            JobView submitted = first.submitNow("grok", job(List.of("pc-a"), "yuni"));
            List<AssignmentView> seen = waiting.get(2, TimeUnit.SECONDS);
            assertEquals(submitted.assignments().get(0).assignmentId(), seen.get(0).assignmentId());
            assertEquals(submitted.jobId(), second.listJobs("grok").get(0).jobId());

            Future<JobView> blocking = pool.submit(() -> first.submit(
                    "grok", job(List.of("pc-a"), "yuni"), Duration.ofSeconds(5)));
            UUID earlier = seen.get(0).assignmentId();
            AssignmentView queued = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (queued == null && System.nanoTime() < deadline) {
                for (AssignmentView row : second.poll("pc-a", Duration.ofMillis(200))) {
                    if (!row.assignmentId().equals(earlier) && row.state() == AssignmentState.QUEUED) {
                        queued = row;
                    }
                }
            }
            assertTrue(queued != null);
            second.finish("pc-a", queued.assignmentId(), true, "from-the-other-process");
            JobView finished = blocking.get(2, TimeUnit.SECONDS);
            assertEquals(AssignmentState.SUCCEEDED, finished.assignments().get(0).state());
            assertEquals(
                    "from-the-other-process",
                    second.getJob("grok", finished.jobId()).assignments().get(0).note());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void list_job_page_is_this_caller_newest_first_without_the_prompt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-12T12:00:00Z"));
        JobStore store = service(clock, null);
        store.register("pc-a", "a.local", List.of("yuni", "miko"));
        JobView older = store.submitNow("grok", job(List.of("pc-a"), "yuni"));
        clock.advance(Duration.ofSeconds(1));
        JobView newer = store.submitNow("grok", job(List.of("pc-a"), "miko"));
        clock.advance(Duration.ofSeconds(1));
        JobView hidden = store.submitNow("alice", job(List.of("pc-a"), "yuni"));
        store.finish("pc-a", newer.assignments().get(0).assignmentId(), true, "shipped");

        JobPage first = store.listJobPage("grok", null, null, 1, null);
        assertEquals(1, first.jobs().size());
        assertEquals(newer.jobId(), first.jobs().get(0).jobId());
        assertEquals("miko", first.jobs().get(0).agentName());
        assertEquals("a.local", first.jobs().get(0).server());
        assertEquals("done", first.jobs().get(0).state());
        assertEquals("shipped", first.jobs().get(0).note());
        assertEquals(Instant.parse("2026-11-12T12:00:01Z"), first.jobs().get(0).submittedAt());

        JobPage second = store.listJobPage("grok", null, null, 1, first.nextCursor());
        assertEquals(List.of(older.jobId()), second.jobs().stream().map(JobSummary::jobId).toList());
        assertEquals("queued", second.jobs().get(0).state());
        assertNull(second.nextCursor());

        JobPage filtered = store.listJobPage("grok", "yuni", "queued", 20, null);
        assertEquals(List.of(older.jobId()), filtered.jobs().stream().map(JobSummary::jobId).toList());
        assertEquals(List.of(), store.listJobPage("alice", null, "done", 20, null).jobs());
        assertEquals(hidden.jobId(), store.listJobPage("alice", null, "queued", 20, null).jobs().get(0).jobId());
        assertThrows(DispatcherException.class, () -> store.listJobPage("grok", null, "later", 20, null));
    }

    @Test
    void rows_in_the_shape_documentjobstore_wrote_are_read_and_finished() {
        String jobId = UUID.randomUUID().toString();
        String assignmentId = UUID.randomUUID().toString();
        java.util.Date created = java.util.Date.from(Instant.parse("2026-11-01T10:00:00Z"));
        // An assignment written before this change: a duplicate assignmentId field and no on_busy.
        assignments.mongoCollection().withDocumentClass(Document.class).insertOne(new Document("_id", assignmentId)
                .append("assignmentId", assignmentId)
                .append("jobId", jobId)
                .append("deviceId", "pc-old")
                .append("agentName", "yuni")
                .append("dispatcherSubject", "grok")
                .append("prompt", "old prompt")
                .append("channel", "TEXT")
                .append("status", "QUEUED")
                .append("ordinal", 0)
                .append("createdAt", created)
                .append("updatedAt", created));
        // A device written before this change: activity and activityText as sub-documents.
        devices.mongoCollection().withDocumentClass(Document.class).insertOne(new Document("_id", "pc-old")
                .append("hostname", "old.local")
                .append("agents", List.of("yuni", "kai"))
                .append("activity", new Document("yuni", "busy").append("kai", "idle"))
                .append("activityText", new Document("yuni", "working").append("kai", ""))
                .append("lastHeartbeat", created));
        JobStore store = store();

        JobView job = store.getJob("grok", UUID.fromString(jobId));
        assertEquals("old prompt", job.prompt());
        assertEquals(OnBusy.supersede, job.assignments().getFirst().onBusy());
        assertEquals(UUID.fromString(assignmentId), job.assignments().getFirst().assignmentId());

        JobPage page = store.listJobPage("grok", null, null, 20, null);
        assertEquals(1, page.jobs().size());
        assertEquals("old.local", page.jobs().getFirst().server());
        assertEquals("queued", page.jobs().getFirst().state());
        assertEquals(created.toInstant(), page.jobs().getFirst().submittedAt());

        List<AssignmentView> queued = store.poll("pc-old", Duration.ZERO);
        assertEquals(1, queued.size());
        assertEquals(UUID.fromString(assignmentId), queued.getFirst().assignmentId());

        var roster = store.listAgents();
        assertTrue(roster.stream().anyMatch(row -> row.name().equals("yuni") && row.state().equals("BUSY")
                && row.activity().equals("working") && row.server().equals("old.local")), roster::toString);
        assertTrue(roster.stream().anyMatch(row -> row.name().equals("kai") && row.state().equals("IDLE")),
                roster::toString);

        AssignmentView finished = store.finish("pc-old", UUID.fromString(assignmentId), true, "done");
        assertEquals(AssignmentState.SUCCEEDED, finished.state());
        assertEquals(AssignmentState.SUCCEEDED, store.getJob("grok", UUID.fromString(jobId)).assignments().getFirst().state());
    }

    private JobStore store() {
        return service(Clock.systemUTC(), null);
    }

    private JobStore service(Clock clock, JobPing ping) {
        return new JobStoreService(assignments, devices, locks, clock, ping);
    }

    /** The stored {@code agent-jobs} rows as Mongo holds them. */
    private List<Document> storedJobs() {
        return assignments.mongoCollection().withDocumentClass(Document.class).find().into(new ArrayList<>());
    }

    private int jobCount() {
        return assignments.mongoCollection().distinct("jobId", String.class).into(new ArrayList<>()).size();
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

        private void advance(Duration step) {
            now = now.plus(step);
        }
    }
}
