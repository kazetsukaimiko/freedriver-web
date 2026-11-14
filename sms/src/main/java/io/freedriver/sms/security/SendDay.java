package io.freedriver.sms.security;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/** One UTC day's send count in {@code daily-send-cap}. {@code _id} is the date. */
@MongoEntity(collection = SendDay.COLLECTION)
public class SendDay {

    static final String COLLECTION = "daily-send-cap";

    @BsonId
    public String id;
    public int used;
}
