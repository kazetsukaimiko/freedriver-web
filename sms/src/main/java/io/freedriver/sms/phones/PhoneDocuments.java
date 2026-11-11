package io.freedriver.sms.phones;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;

/** One {@code phones} document per number. {@code _id} is the number. */
final class PhoneDocuments {

    static final String COLLECTION = "phones";

    private PhoneDocuments() {
    }

    static Document toDocument(PhoneEntry entry) {
        return new Document("_id", entry.phone())
                .append("phone", entry.phone())
                .append("name", entry.name())
                .append("username", entry.username())
                .append("role", entry.role())
                .append("addedAt", Date.from(entry.addedAt()));
    }

    static PhoneEntry toEntry(Document document) {
        return new PhoneEntry(
                document.getString("phone"),
                document.getString("name"),
                document.getString("username"),
                document.getString("role"),
                instant(document.get("addedAt")));
    }

    private static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant stored) {
            return stored;
        }
        throw new IllegalArgumentException("addedAt");
    }
}
