package io.freedriver.sms.phones;

import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Sign-in times on the Dev Services Mongo container. */
@QuarkusTest
class SignInCollectionTest {

    private static final Instant WHEN = Instant.parse("2026-11-11T16:04:00Z");

    @Inject
    SignInRepository signIns;

    @Test
    void a_time_written_by_one_record_is_read_by_another() {
        Clock clock = Clock.fixed(WHEN, ZoneOffset.UTC);
        SignInTimes writer = new SignInTimes(signIns, clock);
        SignInTimes reader = new SignInTimes(signIns, clock);
        String phone = TestNumbers.fresh();

        assertNull(reader.last(phone));
        assertEquals(WHEN, writer.record(phone));
        assertEquals(WHEN, reader.last(phone));
        writer.forget(phone);
        assertNull(reader.last(phone));
    }

    @Test
    void a_sign_in_is_one_document_whose_id_is_the_number() {
        String phone = TestNumbers.fresh();
        new SignInTimes(signIns, Clock.fixed(WHEN, ZoneOffset.UTC)).record(phone);

        Document stored = signIns.mongoCollection().withDocumentClass(Document.class).find(eq("_id", phone)).first();
        assertEquals(phone, stored.get("_id"));
        assertEquals(phone, stored.getString("phone"));
        assertEquals(Date.from(WHEN), stored.get("acceptedAt"));
    }
}
