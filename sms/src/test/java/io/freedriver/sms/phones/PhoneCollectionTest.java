package io.freedriver.sms.phones;

import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The phone list on the Dev Services Mongo container. */
@QuarkusTest
class PhoneCollectionTest {

    private static final Instant ADDED = Instant.parse("2026-11-11T16:00:00Z");

    @Inject
    PhoneRepository phones;

    @Test
    void an_insert_or_delete_is_visible_to_another_directory() {
        PhoneDirectory writer = new PhoneDirectory(phones);
        PhoneDirectory reader = new PhoneDirectory(phones);
        String phone = TestNumbers.fresh();
        PhoneEntry entry = new PhoneEntry(phone, "ada", "ada", "dashboard", ADDED);

        assertTrue(reader.find(phone).isEmpty());
        assertTrue(writer.add(entry));
        assertEquals(entry, reader.find(phone).orElseThrow());
        assertFalse(writer.add(entry));
        assertTrue(writer.remove(phone));
        assertTrue(reader.find(phone).isEmpty());
    }

    @Test
    void a_number_is_one_document_whose_id_and_phone_are_the_number() {
        String phone = TestNumbers.fresh();
        new PhoneDirectory(phones).add(new PhoneEntry(phone, "ada", "ada", "dashboard", ADDED));

        Document stored = phones.mongoCollection().withDocumentClass(Document.class).find(eq("_id", phone)).first();
        assertEquals(phone, stored.get("_id"));
        assertEquals(phone, stored.getString("phone"));
        assertEquals(Date.from(ADDED), stored.get("addedAt"));
    }

    @Test
    void attach_replaces_only_the_pending_username_in_one_update() {
        PhoneDirectory directory = new PhoneDirectory(phones);
        String phone = TestNumbers.fresh();
        String first = "inv" + "a".repeat(32);
        String second = "inv" + "b".repeat(32);
        assertTrue(directory.whitelist(phone, "Ada"));

        assertTrue(directory.attachUsername(phone, first));
        assertTrue(directory.attachUsername(phone, first), "the same username again is accepted");
        assertFalse(directory.attachUsername(phone, second), "another username does not replace it");
        assertFalse(directory.attachUsername(TestNumbers.fresh(), first), "a missing number");
        assertFalse(directory.attachUsername(phone, "not-an-invite-user"));
        assertEquals(first, directory.find(phone).orElseThrow().username());
    }
}
