package io.freedriver.app.schedule;

import io.freedriver.app.appliances.ApplianceAudit;
import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.dashboard.model.InstanceView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The poller on the Dev Services Mongo container, with a recording appliance service. */
@QuarkusTest
class ScheduledSwitchPollerTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final UUID HOUSE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Inject
    ScheduledSwitchRepository rows;

    private final RecordingAudit audit = new RecordingAudit();

    @BeforeEach
    void empty() {
        rows.deleteAll();
    }

    @Test
    void two_pollers_and_twenty_due_rows_run_each_row_once() throws Exception {
        for (int i = 0; i < 20; i++) {
            rows.persist(row("row-" + i, NOW.minusSeconds(20 - i)));
        }
        FakeAppliances appliances = new FakeAppliances(0);
        MutableClock clock = new MutableClock(NOW);
        ScheduledSwitchPoller first = new ScheduledSwitchPoller(rows, appliances, audit, clock);
        ScheduledSwitchPoller second = new ScheduledSwitchPoller(rows, appliances, audit, clock);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> passes = List.of(
                    pool.submit(() -> drain(first)),
                    pool.submit(() -> drain(second)));
            int claimed = 0;
            for (Future<Integer> pass : passes) {
                claimed += pass.get(30, TimeUnit.SECONDS);
            }
            assertEquals(20, claimed);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(20, appliances.runs.size());
        assertTrue(appliances.runs.values().stream().allMatch(count -> count.get() == 1), appliances.runs::toString);
        assertEquals(0, rows.count());
    }

    @Test
    void a_command_that_fails_twice_then_succeeds_runs_three_times_and_is_gone() {
        rows.persist(row("retry", NOW));
        FakeAppliances appliances = new FakeAppliances(2);
        MutableClock clock = new MutableClock(NOW);
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(rows, appliances, audit, clock);

        poller.pass();
        assertEquals(1, runs(appliances, "retry"));
        poller.pass();
        assertEquals(1, runs(appliances, "retry"), "the lease holds the row");

        clock.advance(ScheduledSwitchPoller.LEASE.plusSeconds(1));
        poller.pass();
        clock.advance(ScheduledSwitchPoller.LEASE.plusSeconds(1));
        poller.pass();

        assertEquals(3, runs(appliances, "retry"));
        assertEquals(0, rows.count());
        assertTrue(audit.failed.isEmpty());
    }

    @Test
    void a_third_failure_deletes_the_row_and_audits_failed() {
        rows.persist(row("broken", NOW));
        FakeAppliances appliances = new FakeAppliances(Integer.MAX_VALUE);
        MutableClock clock = new MutableClock(NOW);
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(rows, appliances, audit, clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            poller.pass();
            clock.advance(ScheduledSwitchPoller.LEASE.plusSeconds(1));
        }

        assertEquals(3, runs(appliances, "broken"));
        assertEquals(0, rows.count());
        assertEquals(List.of("broken mcp-switch"), audit.failed);
    }

    @Test
    void a_timeout_is_a_failure_and_the_row_stays_for_the_next_lease() {
        rows.persist(row("slow", NOW));
        FakeAppliances appliances = new FakeAppliances(0);
        appliances.timeout = true;
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(rows, appliances, audit, new MutableClock(NOW));

        poller.pass();

        assertEquals(1, runs(appliances, "slow"));
        assertEquals(1, rows.count());
    }

    @Test
    void a_claim_past_the_third_attempt_runs_nothing() {
        ScheduledSwitch stuck = row("stuck", NOW);
        stuck.attempts = ScheduledSwitchPoller.MAX_ATTEMPTS;
        stuck.claimedUntil = NOW.minusSeconds(1);
        rows.persist(stuck);
        FakeAppliances appliances = new FakeAppliances(0);
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(rows, appliances, audit, new MutableClock(NOW));

        poller.pass();

        assertEquals(0, runs(appliances, "stuck"));
        assertEquals(0, rows.count());
        assertEquals(List.of("stuck mcp-switch"), audit.failed);
    }

    @Test
    void a_future_row_stays() {
        rows.persist(row("later", NOW.plusSeconds(30)));
        FakeAppliances appliances = new FakeAppliances(0);

        assertEquals(0, new ScheduledSwitchPoller(rows, appliances, audit, new MutableClock(NOW)).pass());

        assertEquals(1, rows.count());
    }

    private static int drain(ScheduledSwitchPoller poller) {
        int total = 0;
        int claimed;
        do {
            claimed = poller.pass();
            total += claimed;
        } while (claimed > 0);
        return total;
    }

    private static int runs(FakeAppliances appliances, String name) {
        AtomicInteger count = appliances.runs.get(name);
        return count == null ? 0 : count.get();
    }

    private static ScheduledSwitch row(String name, Instant dueAt) {
        return new ScheduledSwitch(UUID.randomUUID().toString(), HOUSE.toString(), name, true, dueAt, "mcp-switch");
    }

    /** Counts commands by appliance name. The first {@code failures} commands per name throw. */
    static final class FakeAppliances extends ApplianceService {
        final Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        private final int failures;
        volatile boolean timeout;

        FakeAppliances(int failures) {
            super(null, null, null);
            this.failures = failures;
        }

        @Override
        public InstanceView issueCommand(UUID instanceId, String applianceName, boolean on, String subject) {
            int count = runs.computeIfAbsent(applianceName, name -> new AtomicInteger()).incrementAndGet();
            if (count <= failures) {
                throw new IllegalStateException("command refused");
            }
            return new InstanceView(instanceId, "home", null, false, timeout, List.of());
        }
    }

    static final class RecordingAudit extends ApplianceAudit {
        final List<String> failed = new CopyOnWriteArrayList<>();

        @Override
        public void record(Event event) {
            if ("failed".equals(event.result())) {
                failed.add(event.applianceName() + " " + event.subject());
            }
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration step) {
            now = now.plus(step);
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
