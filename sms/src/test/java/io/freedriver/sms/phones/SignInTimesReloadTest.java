package io.freedriver.sms.phones;

import io.freedriver.sms.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignInTimesReloadTest {

    private static final Instant WHEN = Instant.parse("2026-11-11T15:04:00Z");

    @TempDir
    Path dataDir;

    @Test
    void a_time_written_by_one_record_is_read_by_another() {
        Clock clock = Clock.fixed(WHEN, ZoneOffset.UTC);
        SignInTimes reader = new SignInTimes(dataDir, Json.mapper(), clock);
        SignInTimes writer = new SignInTimes(dataDir, Json.mapper(), clock);
        assertNull(reader.last("+15555550123"));

        assertEquals(WHEN, writer.record("+15555550123"));

        assertEquals(WHEN, reader.last("+15555550123"));
    }
}
