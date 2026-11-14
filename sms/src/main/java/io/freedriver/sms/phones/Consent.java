package io.freedriver.sms.phones;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/**
 * One agreement in {@code consents}. Insert only. A row imported from {@code consents.jsonl} has its
 * line number as {@code _id}; a new row has a random UUID.
 */
@MongoEntity(collection = Consent.COLLECTION)
public class Consent {

    static final String COLLECTION = "consents";

    @BsonId
    public String id;
    public String phone;
    public ConsentPurpose purpose;
    public String wording;
    public ConsentSource source;
    public Instant givenAt;
    public String inviteId;

    static Consent of(String id, ConsentRecord record) {
        Consent row = new Consent();
        row.id = id;
        row.phone = record.phone();
        row.purpose = record.purpose();
        row.wording = record.wording();
        row.source = record.source();
        row.givenAt = record.givenAt();
        row.inviteId = record.inviteId();
        return row;
    }

    ConsentRecord record() {
        return new ConsentRecord(phone, purpose, wording, source, givenAt, inviteId);
    }
}
