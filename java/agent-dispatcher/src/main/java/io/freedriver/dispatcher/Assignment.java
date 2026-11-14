package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.OnBusy;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;

import java.time.Instant;

/** One device's share of a job, in {@code agent-jobs}. Field names are the stored names. */
@MongoEntity(collection = Assignment.COLLECTION)
public class Assignment {

    static final String COLLECTION = "agent-jobs";

    @BsonId
    public String id;
    public String jobId;
    public String deviceId;
    public String agentName;
    public String dispatcherSubject;
    public String prompt;
    public Channel channel;
    @BsonProperty("on_busy")
    public OnBusy onBusy;
    public AssignmentState status;
    public int ordinal;
    public String sessionId;
    public String note;
    public Instant createdAt;
    public Instant updatedAt;

    boolean terminal() {
        return AssignmentRepository.TERMINAL.contains(status);
    }
}
