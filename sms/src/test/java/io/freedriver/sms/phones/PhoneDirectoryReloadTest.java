package io.freedriver.sms.phones;

import io.freedriver.sms.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneDirectoryReloadTest {

    private static final Instant ADDED = Instant.parse("2026-11-11T15:00:00Z");

    @TempDir
    Path dataDir;

    @Test
    void a_number_written_by_one_directory_is_listed_by_another() {
        PhoneDirectory reader = new PhoneDirectory(dataDir, Json.mapper());
        PhoneDirectory writer = new PhoneDirectory(dataDir, Json.mapper());
        assertTrue(reader.list().isEmpty());

        PhoneEntry entry = new PhoneEntry("+15555550123", "ada", "ada", "dashboard", ADDED);
        assertTrue(writer.add(entry));

        assertEquals(entry, reader.find("+15555550123").orElseThrow());
        assertEquals(writer.list(), reader.list());
    }
}
