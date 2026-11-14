package io.freedriver.app.schedule;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/**
 * One row in {@code scheduled-switches}. {@code claimedUntil} is the lease of the replica running
 * it, and {@code attempts} counts the claims.
 */
@MongoEntity(collection = ScheduledSwitch.COLLECTION)
public class ScheduledSwitch {

    static final String COLLECTION = "scheduled-switches";

    @BsonId
    public String id;
    /** The house's instance id as text, as rows have always stored it. */
    public String instanceId;
    public String applianceName;
    public boolean on;
    public Instant dueAt;
    /** The caller who asked for the switch. The audit names this subject. */
    public String subject;
    public Instant claimedUntil;
    public int attempts;

    public ScheduledSwitch() {
    }

    public ScheduledSwitch(String id, String instanceId, String applianceName, boolean on, Instant dueAt,
                           String subject) {
        this.id = id;
        this.instanceId = instanceId;
        this.applianceName = applianceName;
        this.on = on;
        this.dueAt = dueAt;
        this.subject = subject;
    }
}
