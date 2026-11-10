package io.freedriver.app.jobs;

import com.mongodb.client.MongoClient;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portal-admin list of dispatch jobs copied into {@code agent-jobs}.
 * The session is the same cookie session as {@code /api/phones}.
 */
@Path("/api/jobs")
@RolesAllowed("portal-admin")
@Produces(MediaType.APPLICATION_JSON)
public class AgentJobsResource {

    private final MongoClient mongo;
    private final String database;

    @Inject
    public AgentJobsResource(
            MongoClient mongo,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database) {
        this.mongo = mongo;
        this.database = database;
    }

    @GET
    public JobList list() {
        List<JobRow> jobs = new ArrayList<>();
        for (Document document : mongo.getDatabase(database).getCollection("agent-jobs").find()) {
            jobs.add(row(document));
        }
        return new JobList(List.copyOf(jobs));
    }

    private static JobRow row(Document document) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            raw.put(entry.getKey(), json(entry.getValue()));
        }
        return new JobRow(
                text(document.get("assignmentId"), document.get("_id")),
                text(document.get("jobId"), null),
                text(document.get("prompt"), null),
                text(document.get("channel"), null),
                text(document.get("status"), null),
                text(document.get("note"), null),
                raw);
    }

    private static Object json(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Document nested) {
            Map<String, Object> raw = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : nested.entrySet()) {
                raw.put(entry.getKey(), json(entry.getValue()));
            }
            return raw;
        }
        return value;
    }

    private static String text(Object value, Object fallback) {
        Object chosen = value == null ? fallback : value;
        return chosen == null ? "" : chosen.toString();
    }

    public record JobList(List<JobRow> jobs) {
    }

    public record JobRow(
            String assignmentId,
            String jobId,
            String prompt,
            String channel,
            String status,
            String note,
            Map<String, Object> document) {
    }
}
