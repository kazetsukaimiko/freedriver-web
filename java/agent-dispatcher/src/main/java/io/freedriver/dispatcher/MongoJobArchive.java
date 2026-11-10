package io.freedriver.dispatcher;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.ReplaceOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

/**
 * Writes {@code agent-jobs} in the dashboard database. A missing database logs and skips,
 * and the in-memory queue still accepts the job.
 */
@ApplicationScoped
class MongoJobArchive implements JobArchive {

    private static final Logger LOG = Logger.getLogger(MongoJobArchive.class);
    private static final String COLLECTION = "agent-jobs";

    private final MongoClient client;
    private final String database;
    private final boolean enabled;

    @Inject
    MongoJobArchive(
            MongoClient client,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database,
            @ConfigProperty(name = "freedriver.dispatcher.job-archive", defaultValue = "mongo") String mode) {
        this.client = client;
        this.database = database;
        this.enabled = "mongo".equals(mode);
    }

    @Override
    public void save(StoredJob job) {
        if (!enabled || job == null) {
            return;
        }
        try {
            client.getDatabase(database)
                    .getCollection(COLLECTION)
                    .replaceOne(eq("_id", job.assignmentId()), document(job), new ReplaceOptions().upsert(true));
        } catch (RuntimeException failure) {
            LOG.warn("agent-jobs write skipped");
        }
    }

    @Override
    public List<StoredJob> load() {
        if (!enabled) {
            return List.of();
        }
        try {
            List<StoredJob> jobs = new ArrayList<>();
            for (Document document : client.getDatabase(database).getCollection(COLLECTION).find()) {
                StoredJob job = read(document);
                if (job != null) {
                    jobs.add(job);
                }
            }
            return List.copyOf(jobs);
        } catch (RuntimeException failure) {
            LOG.warn("agent-jobs load skipped");
            return List.of();
        }
    }

    private static Document document(StoredJob job) {
        Document document = new Document("_id", job.assignmentId())
                .append("assignmentId", job.assignmentId())
                .append("jobId", job.jobId())
                .append("deviceId", job.deviceId())
                .append("agentName", job.agentName())
                .append("dispatcherSubject", job.dispatcherSubject())
                .append("prompt", job.prompt())
                .append("channel", job.channel())
                .append("status", job.status())
                .append("createdAt", date(job.createdAt()))
                .append("updatedAt", date(job.updatedAt()));
        if (job.note() != null) {
            document.append("note", job.note());
        }
        if (job.sessionId() != null) {
            document.append("sessionId", job.sessionId());
        }
        return document;
    }

    private static StoredJob read(Document document) {
        String assignmentId = text(document.get("assignmentId"));
        if (assignmentId == null) {
            Object id = document.get("_id");
            assignmentId = id == null ? null : id.toString();
        }
        if (assignmentId == null || text(document.get("jobId")) == null) {
            return null;
        }
        return new StoredJob(
                assignmentId,
                text(document.get("jobId")),
                text(document.get("deviceId")),
                text(document.get("agentName")),
                text(document.get("dispatcherSubject")),
                text(document.get("prompt")),
                text(document.get("channel")),
                text(document.get("status")),
                text(document.get("note")),
                text(document.get("sessionId")),
                instant(document.get("createdAt")),
                instant(document.get("updatedAt")));
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof String text) {
            return Instant.parse(text);
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
