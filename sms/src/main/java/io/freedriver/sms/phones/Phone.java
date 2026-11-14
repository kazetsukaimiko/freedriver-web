package io.freedriver.sms.phones;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/** One listed number in {@code phones}. {@code _id} and {@code phone} both hold the number, as stored. */
@MongoEntity(collection = Phone.COLLECTION)
public class Phone {

    static final String COLLECTION = "phones";

    @BsonId
    public String id;
    public String phone;
    public String name;
    public String username;
    public String role;
    public Instant addedAt;

    static Phone of(PhoneEntry entry) {
        Phone row = new Phone();
        row.id = entry.phone();
        row.phone = entry.phone();
        row.name = entry.name();
        row.username = entry.username();
        row.role = entry.role();
        row.addedAt = entry.addedAt();
        return row;
    }

    PhoneEntry entry() {
        return new PhoneEntry(phone, name, username, role, addedAt);
    }
}
