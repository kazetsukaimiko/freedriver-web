package io.freedriver.dispatcher;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/** One in-flight submit wait per dispatcher subject, in {@code agent-locks}. */
@MongoEntity(collection = SubmitLock.COLLECTION)
public class SubmitLock {

    static final String COLLECTION = "agent-locks";

    /** The dispatcher subject. */
    @BsonId
    public String id;
    public Instant expiresAt;
}
