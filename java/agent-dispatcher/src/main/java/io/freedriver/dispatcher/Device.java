package io.freedriver.dispatcher;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One host and the agents it serves, in {@code agent-devices}. */
@MongoEntity(collection = Device.COLLECTION)
public class Device {

    static final String COLLECTION = "agent-devices";

    @BsonId
    public String id;
    public String hostname;
    public List<String> agents;
    /** Agent name to idle or busy. */
    public Map<String, String> activity;
    /** Agent name to its short activity note. */
    public Map<String, String> activityText;
    /** The time of the host's last report. */
    public Instant lastHeartbeat;
    /** The time of the host's last take call, from the server clock. Absent on rows written before it existed. */
    public Instant lastTake;
}
