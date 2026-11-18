package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two service instances on one Dev Services Mongo container, as two dispatcher replicas would be. */
@QuarkusTest
class JobStoreReplicaTest {

    private static final int ROUNDS = 10;

    @Inject
    AssignmentRepository assignments;

    @Inject
    DeviceRepository devices;

    @Inject
    SubmitLocks locks;

    @Inject
    JobStore bean;

    private JobStore first;
    private JobStore second;

    @BeforeEach
    void replicas() {
        assignments.deleteAll();
        devices.deleteAll();
        locks.deleteAll();
        first = new JobStoreService(assignments, devices, locks, Clock.systemUTC(), null);
        second = new JobStoreService(assignments, devices, locks, Clock.systemUTC(), null);
    }

    @Test
    void the_bean_reads_a_device_the_other_replica_wrote() {
        DeviceView registered = first.register("pc-a", "a.local", List.of("yuni"));

        assertEquals(registered.hostname(), bean.listDevices().getFirst().hostname());
        assertEquals("pc-a", bean.deviceIdFor("a.local", "yuni"));
    }

    @Test
    void two_replicas_race_a_claim_and_exactly_one_wins() throws Exception {
        first.register("pc-a", "a.local", List.of("yuni"));
        for (int round = 0; round < ROUNDS; round++) {
            UUID assignment = queued(first).assignmentId();

            List<Object> outcomes = race(
                    () -> first.claim("pc-a", assignment),
                    () -> second.claim("pc-a", assignment));

            assertOneWinner(outcomes);
            assertEquals(AssignmentState.CLAIMED, first.assignment("pc-a", assignment).state());
        }
    }

    @Test
    void two_replicas_race_a_finish_and_exactly_one_wins() throws Exception {
        first.register("pc-a", "a.local", List.of("yuni"));
        for (int round = 0; round < ROUNDS; round++) {
            UUID assignment = queued(first).assignmentId();

            List<Object> outcomes = race(
                    () -> first.finish("pc-a", assignment, true, "first"),
                    () -> second.finish("pc-a", assignment, false, "second"));

            assertOneWinner(outcomes);
            AssignmentView stored = second.assignment("pc-a", assignment);
            AssignmentView winner = (AssignmentView) outcomes.stream()
                    .filter(AssignmentView.class::isInstance).findFirst().orElseThrow();
            assertEquals(winner.state(), stored.state());
            assertEquals(winner.note(), stored.note());
        }
    }

    /**
     * The connector's finish (host route) races the agent's finish_job on one assignment. Whoever
     * wins, the stored row is the winner's, the host call answers exactly that stored row, and the
     * agent's call either is the winner or is refused with the state the host stored (#590).
     */
    @Test
    void the_host_finish_races_finish_job_and_answers_the_stored_winner() throws Exception {
        first.register("pc-a", "a.local", List.of("yuni"));
        for (int round = 0; round < ROUNDS; round++) {
            UUID assignment = queued(first).assignmentId();

            List<Object> outcomes = race(
                    () -> first.finish("pc-a", assignment, true, "the agent's result"),
                    () -> second.finishIfOpen("pc-a", assignment, false, "the turn ended without a result"));

            AssignmentView stored = second.assignment("pc-a", assignment);
            assertEquals(stored, outcomes.get(1), "the host call answers the stored row");
            Object agent = outcomes.get(0);
            if (agent instanceof AssignmentView won) {
                assertEquals(stored, won);
                assertEquals("the agent's result", stored.note());
            } else {
                assertTrue(agent instanceof DispatcherException, String.valueOf(agent));
                assertEquals("assignment is FAILED", ((DispatcherException) agent).getMessage());
                assertEquals(AssignmentState.FAILED, stored.state());
                assertEquals("the turn ended without a result", stored.note());
            }
        }
    }

    @Test
    void two_replicas_race_take_jobs_and_each_job_is_returned_once() throws Exception {
        first.register("pc-a", "a.local", List.of("yuni"));
        for (int round = 0; round < ROUNDS; round++) {
            assignments.deleteAll();
            Set<UUID> queued = new HashSet<>();
            for (int job = 0; job < 6; job++) {
                queued.add(queued(first).assignmentId());
            }

            List<Object> outcomes = race(
                    () -> first.take("pc-a", List.of(), Duration.ZERO),
                    () -> second.take("pc-a", List.of(), Duration.ZERO));

            List<UUID> returned = new ArrayList<>();
            for (Object outcome : outcomes) {
                assertTrue(outcome instanceof List<?>, String.valueOf(outcome));
                for (Object row : (List<?>) outcome) {
                    returned.add(((AssignmentView) row).assignmentId());
                }
            }
            assertEquals(queued.size(), returned.size(), returned::toString);
            assertEquals(queued, new HashSet<>(returned));
            for (UUID id : queued) {
                assertEquals(AssignmentState.CLAIMED, first.assignment("pc-a", id).state());
            }
        }
    }

    @Test
    void two_replicas_race_the_submit_lock_and_one_wins() throws Exception {
        first.register("pc-a", "a.local", List.of("yuni"));
        for (int round = 0; round < ROUNDS; round++) {
            String subject = "grok-" + round;

            List<Object> outcomes = race(
                    () -> first.submit(subject, job(), Duration.ofMillis(300)),
                    () -> second.submit(subject, job(), Duration.ofMillis(300)));

            assertOneWinner(outcomes);
            DispatcherException refused = (DispatcherException) outcomes.stream()
                    .filter(DispatcherException.class::isInstance).findFirst().orElseThrow();
            assertEquals("submit already in flight", refused.getMessage());
        }
    }

    @Test
    void the_lock_upsert_lets_one_holder_in_and_takes_over_an_expired_lock() throws Exception {
        Instant now = Instant.parse("2026-11-13T12:00:00Z");
        List<Object> outcomes = race(
                () -> locks.acquire("grok", now, JobStoreService.LOCK_TTL),
                () -> locks.acquire("grok", now, JobStoreService.LOCK_TTL));

        assertEquals(1, outcomes.stream().filter(Boolean.TRUE::equals).count(), outcomes::toString);
        assertTrue(locks.acquire("grok", now.plus(JobStoreService.LOCK_TTL), JobStoreService.LOCK_TTL),
                "an expired lock is taken over");
    }

    private static AssignmentView queued(JobStore store) {
        JobView job = store.submitNow("grok", job());
        return job.assignments().getFirst();
    }

    private static SubmitJobRequest job() {
        return new SubmitJobRequest(List.of("pc-a"), "yuni", "say hello", Channel.TEXT);
    }

    /** Starts both calls together. Each outcome is the call's result or the exception it threw. */
    private static List<Object> race(Callable<?> one, Callable<?> two) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> call : List.of(one, two)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    outcomes.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException failed) {
                    outcomes.add(failed.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertOneWinner(List<Object> outcomes) {
        long refused = outcomes.stream().filter(DispatcherException.class::isInstance).count();
        assertEquals(1, refused, outcomes::toString);
        DispatcherException loser = (DispatcherException) outcomes.stream()
                .filter(DispatcherException.class::isInstance).findFirst().orElseThrow();
        assertEquals(DispatcherException.Kind.CONFLICT, loser.kind());
        assertEquals(1, outcomes.stream().filter(outcome -> !(outcome instanceof Throwable)).count(), outcomes::toString);
    }
}
