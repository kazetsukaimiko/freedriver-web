package io.freedriver.sms.phones;

import io.freedriver.sms.support.Json;
import io.freedriver.sms.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup seeding of kaze's number from FREEDRIVER_SEED_PHONE, against a phone list on disk. */
class PhoneListSeedTest {

    private static final String SEED = "+15555558168";
    private static final Instant FIRST_START = Instant.parse("2026-09-28T05:00:00Z");
    private static final Instant SECOND_START = Instant.parse("2026-09-29T05:00:00Z");

    @TempDir
    Path dataDir;

    /** One service start: a fresh phone list read from disk and the seed step. */
    private PhoneDirectory start(Optional<String> seed, Instant now) {
        PhoneDirectory directory = new PhoneDirectory(dataDir, Json.mapper());
        new PhoneListSeed(seed, directory, Clock.fixed(now, ZoneOffset.UTC)).seed();
        return directory;
    }

    @Test
    void seeds_the_number_once_as_kaze_with_dashboard() {
        PhoneDirectory directory = start(Optional.of(SEED), FIRST_START);

        assertEquals(List.of(new PhoneEntry(SEED, "kaze", "kaze", "dashboard", FIRST_START)), directory.list());
    }

    @Test
    void a_second_startup_keeps_the_one_seed_row() {
        start(Optional.of(SEED), FIRST_START);

        try (LogCapture logs = LogCapture.open()) {
            PhoneDirectory directory = start(Optional.of(SEED), SECOND_START);

            assertEquals(List.of(new PhoneEntry(SEED, "kaze", "kaze", "dashboard", FIRST_START)), directory.list());
            assertTrue(logs.all().contains("Seed row already present (***68)"), logs.all());
        }
    }

    @Test
    void an_empty_setting_adds_nothing() {
        try (LogCapture logs = LogCapture.open()) {
            PhoneDirectory directory = start(Optional.empty(), FIRST_START);

            assertEquals(List.of(), directory.list());
            assertFalse(logs.all().contains("Seed row"), logs.all());
        }
    }

    @Test
    void an_existing_row_for_the_number_is_left_unchanged() {
        PhoneEntry existing = new PhoneEntry(SEED, "Mom", "mom.user", "viewer", Instant.parse("2026-09-01T12:00:00Z"));
        new PhoneDirectory(dataDir, Json.mapper()).add(existing);

        PhoneDirectory directory = start(Optional.of(SEED), FIRST_START);

        assertEquals(List.of(existing), directory.list());
    }

    @Test
    void changing_the_setting_only_adds_the_new_number() {
        String next = "+15555558169";
        start(Optional.of(SEED), FIRST_START);

        PhoneDirectory directory = start(Optional.of(next), SECOND_START);

        assertEquals(List.of(
                new PhoneEntry(SEED, "kaze", "kaze", "dashboard", FIRST_START),
                new PhoneEntry(next, "kaze", "kaze", "dashboard", SECOND_START)), directory.list());
    }

    @Test
    void seed_logs_show_at_most_the_last_two_digits() {
        try (LogCapture logs = LogCapture.open()) {
            start(Optional.of(SEED), FIRST_START);
            start(Optional.of(SEED), SECOND_START);

            String out = logs.all();
            assertTrue(out.contains("Seed row added (***68)"), out);
            assertTrue(out.contains("Seed row already present (***68)"), out);
            assertFalse(out.contains("168"), "more than two digits logged: " + out);
        }
    }
}
