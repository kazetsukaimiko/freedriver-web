package io.freedriver.app.schedule;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledSwitchPollerTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final UUID HOUSE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void a_due_row_runs_and_a_future_row_stays() {
        MemorySwitches store = new MemorySwitches();
        store.insert(row("due", NOW.minusSeconds(1), false));
        store.insert(row("later", NOW.plusSeconds(30), true));
        RecordingRunner runner = new RecordingRunner();
        poller(store, runner, new RedisLockVendor(new RedisLockVendorTest.MemoryCommands()), clock(NOW))
                .poll(NOW);
        assertEquals(List.of("due"), runner.ids);
        assertEquals(List.of("later"), store.ids());
    }

    @Test
    void a_held_lock_skips_the_pass() throws Exception {
        MemorySwitches store = new MemorySwitches();
        store.insert(row("due", NOW, false));
        RedisLockVendorTest.MemoryCommands commands = new RedisLockVendorTest.MemoryCommands();
        RedisLockVendor locks = new RedisLockVendor(commands);
        try (AutoCloseable ignored = locks.apply(ScheduledSwitchPoller.class.getSimpleName(), Duration.ofSeconds(45))) {
            RecordingRunner runner = new RecordingRunner();
            poller(store, runner, locks, clock(NOW)).poll(NOW);
            assertTrue(runner.ids.isEmpty());
            assertEquals(List.of("due"), store.ids());
        }
    }

    @Test
    void a_failed_command_is_still_deleted() {
        MemorySwitches store = new MemorySwitches();
        store.insert(row("due", NOW, false));
        RecordingRunner runner = new RecordingRunner();
        runner.fail = true;
        poller(store, runner, new RedisLockVendor(new RedisLockVendorTest.MemoryCommands()), clock(NOW)).poll(NOW);
        assertEquals(List.of("due"), runner.ids);
        assertTrue(store.ids().isEmpty());
    }

    private static ScheduledSwitchPoller poller(
            ScheduledSwitches store, SwitchRunner runner, RedisLockVendor locks, Clock clock) {
        return new ScheduledSwitchPoller(store, runner, locks, clock, false);
    }

    private static Clock clock(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private static ScheduledSwitch row(String id, Instant dueAt, boolean on) {
        return new ScheduledSwitch(id, HOUSE, "fridge", on, dueAt, "mcp-switch");
    }

    static final class MemorySwitches implements ScheduledSwitches {
        final List<ScheduledSwitch> rows = new ArrayList<>();

        @Override
        public void insert(ScheduledSwitch row) {
            rows.add(row);
        }

        @Override
        public List<ScheduledSwitch> due(Instant now) {
            return rows.stream().filter(row -> !row.dueAt().isAfter(now)).toList();
        }

        @Override
        public void delete(String id) {
            rows.removeIf(row -> row.id().equals(id));
        }

        List<String> ids() {
            return rows.stream().map(ScheduledSwitch::id).toList();
        }
    }

    static final class RecordingRunner implements SwitchRunner {
        final List<String> ids = new ArrayList<>();
        boolean fail;

        @Override
        public void run(ScheduledSwitch row) {
            ids.add(row.id());
            if (fail) {
                throw new IllegalStateException("down");
            }
        }
    }
}
