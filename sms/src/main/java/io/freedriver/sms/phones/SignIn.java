package io.freedriver.sms.phones;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/** When one listed number last accepted a code, in {@code sign-ins}. {@code _id} is the number. */
@MongoEntity(collection = SignIn.COLLECTION)
public class SignIn {

    static final String COLLECTION = "sign-ins";

    @BsonId
    public String id;
    public String phone;
    public Instant acceptedAt;
}
