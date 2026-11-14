package io.freedriver.app.invites;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/**
 * One single-use invite link in {@code invites}. {@code _id} is the invite id. {@code codeHash} is
 * the SHA-256 of the code; the code is never stored.
 */
@MongoEntity(collection = Invite.COLLECTION)
public class Invite {

    static final String COLLECTION = "invites";

    @BsonId
    public String id;
    public String name;
    public String codeHash;
    public Instant createdAt;
    public Instant expiresAt;
    public Instant usedAt;
    public Instant cancelledAt;
    public Integer submissions;
    public Integer codesSent;
    public String pendingPhone;

    int submissionCount() {
        return submissions == null ? 0 : submissions;
    }

    int codeCount() {
        return codesSent == null ? 0 : codesSent;
    }
}
