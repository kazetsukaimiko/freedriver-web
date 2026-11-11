package io.freedriver.sms.phones;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;

/** One {@code sign-ins} document per number. {@code _id} is the number. */
final class SignInDocuments {

    static final String COLLECTION = "sign-ins";

    private SignInDocuments() {
    }

    static Document toDocument(String phone, Instant when) {
        return new Document("_id", phone)
                .append("phone", phone)
                .append("acceptedAt", Date.from(when));
    }

    static Instant acceptedAt(Document document) {
        Object value = document.get("acceptedAt");
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant stored) {
            return stored;
        }
        throw new IllegalArgumentException("acceptedAt");
    }
}
