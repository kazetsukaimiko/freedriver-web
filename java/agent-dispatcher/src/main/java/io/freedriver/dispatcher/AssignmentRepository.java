package io.freedriver.dispatcher;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.freedriver.dispatcher.model.AssignmentState;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;

/**
 * {@code agent-jobs}. A state change is one {@code findOneAndUpdate} filtered on the id, the device,
 * and the allowed current states, so two processes cannot both make it.
 */
@ApplicationScoped
public class AssignmentRepository implements PanacheMongoRepositoryBase<Assignment, String> {

    static final Set<AssignmentState> TERMINAL =
            EnumSet.of(AssignmentState.SUCCEEDED, AssignmentState.FAILED, AssignmentState.EXPIRED);
    static final Set<AssignmentState> OPEN =
            EnumSet.of(AssignmentState.QUEUED, AssignmentState.CLAIMED, AssignmentState.RUNNING);

    /**
     * Moves the assignment from one of {@code from} to {@code to}. {@code note} and
     * {@code sessionId} are set only when not null. Null when nothing matched.
     */
    Assignment transition(String id, String deviceId, Set<AssignmentState> from, AssignmentState to,
                          String note, String sessionId, Instant now) {
        List<Bson> changes = new ArrayList<>();
        changes.add(Updates.set("status", to.name()));
        changes.add(Updates.set("updatedAt", Date.from(now)));
        if (note != null) {
            changes.add(Updates.set("note", note));
        }
        if (sessionId != null) {
            changes.add(Updates.set("sessionId", sessionId));
        }
        return mongoCollection().findOneAndUpdate(
                and(eq("_id", id), eq("deviceId", deviceId), in("status", names(from))),
                Updates.combine(changes),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** Index {@code {deviceId, status, ordinal}}. */
    List<Assignment> queuedFor(String deviceId) {
        return mongoCollection()
                .find(and(eq("deviceId", deviceId), eq("status", AssignmentState.QUEUED.name())))
                .sort(Sorts.ascending("ordinal"))
                .into(new ArrayList<>());
    }

    /**
     * Every caller's assignments, newest first, after the cursor row when one is given.
     * Index {@code {createdAt, _id}}, both descending.
     */
    List<Assignment> newest(int limit, Instant afterCreatedAt, String afterId) {
        Bson filter = ne("jobId", null);
        if (afterCreatedAt != null) {
            Date at = Date.from(afterCreatedAt);
            filter = and(filter, or(lt("createdAt", at), and(eq("createdAt", at), lt("_id", afterId))));
        }
        return mongoCollection().find(filter)
                .sort(Sorts.descending("createdAt", "_id"))
                .limit(limit)
                .into(new ArrayList<>());
    }

    /** Index {@code {jobId}}. */
    List<Assignment> forJob(String jobId) {
        return mongoCollection().find(eq("jobId", jobId)).sort(Sorts.ascending("ordinal")).into(new ArrayList<>());
    }

    /** Index {@code {dispatcherSubject, createdAt}}, oldest first. */
    List<Assignment> forSubject(String dispatcherSubject) {
        return mongoCollection()
                .find(eq("dispatcherSubject", dispatcherSubject))
                .sort(Sorts.ascending("createdAt"))
                .into(new ArrayList<>());
    }

    /**
     * One page of the caller's jobs, newest first: match the subject (index
     * {@code {dispatcherSubject, createdAt}}), group by job, filter, sort, limit. The rows carry no
     * prompt. Each row has {@code _id} (the job), {@code agentName}, {@code submittedAt},
     * {@code state}, {@code notes} in ordinal order, and {@code device} from {@code agent-devices}.
     */
    List<Document> page(String dispatcherSubject, String agentName, String state, int limit,
                        Instant afterSubmittedAt, String afterJobId) {
        Document match = new Document("dispatcherSubject", dispatcherSubject).append("jobId", new Document("$ne", null));
        if (agentName != null) {
            match.append("agentName", agentName);
        }
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", match));
        pipeline.add(new Document("$sort", new Document("ordinal", 1)));
        pipeline.add(new Document("$group", new Document("_id", "$jobId")
                .append("agentName", new Document("$first", "$agentName"))
                .append("deviceId", new Document("$first", "$deviceId"))
                .append("submittedAt", new Document("$min", "$createdAt"))
                .append("statuses", new Document("$push", "$status"))
                .append("notes", new Document("$push", new Document("status", "$status").append("note", "$note")))));
        pipeline.add(new Document("$addFields", new Document("state", new Document("$cond", List.of(
                new Document("$or", List.of(
                        new Document("$in", List.of(AssignmentState.CLAIMED.name(), "$statuses")),
                        new Document("$in", List.of(AssignmentState.RUNNING.name(), "$statuses")))),
                "in-progress",
                new Document("$cond", List.of(
                        new Document("$in", List.of(AssignmentState.QUEUED.name(), "$statuses")),
                        "queued",
                        "done")))))));
        if (state != null) {
            pipeline.add(new Document("$match", new Document("state", state)));
        }
        if (afterSubmittedAt != null) {
            Date at = Date.from(afterSubmittedAt);
            pipeline.add(new Document("$match", new Document("$or", List.of(
                    new Document("submittedAt", new Document("$lt", at)),
                    new Document("submittedAt", at).append("_id", new Document("$lt", afterJobId))))));
        }
        pipeline.add(new Document("$sort", new Document("submittedAt", -1).append("_id", -1)));
        pipeline.add(new Document("$limit", limit));
        pipeline.add(new Document("$lookup", new Document("from", Device.COLLECTION)
                .append("localField", "deviceId")
                .append("foreignField", "_id")
                .append("as", "device")));
        pipeline.add(new Document("$project", new Document("statuses", 0)));
        return mongoCollection().aggregate(pipeline, Document.class).into(new ArrayList<>());
    }

    private static List<String> names(Set<AssignmentState> states) {
        return states.stream().map(Enum::name).toList();
    }
}
