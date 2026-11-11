package io.freedriver.sms.phones;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignInCollectionTest {

    private static final Instant WHEN = Instant.parse("2026-11-11T16:04:00Z");

    @Test
    void a_time_written_by_one_record_is_read_by_another() {
        MemorySignInRecords store = new MemorySignInRecords();
        Clock clock = Clock.fixed(WHEN, ZoneOffset.UTC);
        SignInTimes writer = new SignInTimes(store, clock);
        SignInTimes reader = new SignInTimes(store, clock);

        assertNull(reader.last("+15555550123"));
        assertEquals(WHEN, writer.record("+15555550123"));
        assertEquals(WHEN, reader.last("+15555550123"));
        writer.forget("+15555550123");
        assertNull(reader.last("+15555550123"));
    }

    @Test
    void a_sign_in_is_one_document_whose_id_is_the_number() {
        Document document = SignInDocuments.toDocument("+15555550123", WHEN);

        assertEquals("+15555550123", document.get("_id"));
        assertEquals("+15555550123", document.getString("phone"));
        assertEquals(Date.from(WHEN), document.get("acceptedAt"));
        assertEquals(WHEN, SignInDocuments.acceptedAt(document));
    }
}
