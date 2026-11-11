package io.freedriver.sms.phones;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneCollectionTest {

    private static final Instant ADDED = Instant.parse("2026-11-11T16:00:00Z");

    @Test
    void an_insert_or_delete_is_visible_to_another_directory() {
        MemoryPhoneRecords store = new MemoryPhoneRecords();
        PhoneDirectory writer = new PhoneDirectory(store);
        PhoneDirectory reader = new PhoneDirectory(store);
        PhoneEntry entry = new PhoneEntry("+15555550123", "ada", "ada", "dashboard", ADDED);

        assertTrue(reader.list().isEmpty());
        assertTrue(writer.add(entry));
        assertEquals(entry, reader.find("+15555550123").orElseThrow());
        assertFalse(writer.add(entry));
        assertTrue(writer.remove("+15555550123"));
        assertTrue(reader.find("+15555550123").isEmpty());
    }

    @Test
    void a_number_is_one_document_whose_id_is_the_number() {
        PhoneEntry entry = new PhoneEntry("+15555550123", "ada", "ada", "dashboard", ADDED);
        Document document = PhoneDocuments.toDocument(entry);

        assertEquals("+15555550123", document.get("_id"));
        assertEquals("+15555550123", document.getString("phone"));
        assertEquals(Date.from(ADDED), document.get("addedAt"));
        assertEquals(entry, PhoneDocuments.toEntry(document));
    }
}
