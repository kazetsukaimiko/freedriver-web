package io.freedriver.app.schedule;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Sorts.ascending;

/** {@code scheduled-switches} in the dashboard database. */
@ApplicationScoped
class MongoScheduledSwitches implements ScheduledSwitches {

    static final String COLLECTION = "scheduled-switches";
    private static final int DUE_BATCH = 8;

    private final MongoCollection<Document> rows;
    private volatile boolean indexed;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    MongoScheduledSwitches() {
        this.rows = null;
    }

    @Inject
    MongoScheduledSwitches(
            MongoClient client,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database) {
        this.rows = client.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public void insert(ScheduledSwitch row) {
        ensureIndex();
        rows.insertOne(document(row));
    }

    @Override
    public List<ScheduledSwitch> due(Instant now) {
        ensureIndex();
        List<ScheduledSwitch> out = new ArrayList<>();
        for (Document document : rows.find(lte("dueAt", Date.from(now))).sort(ascending("dueAt")).limit(DUE_BATCH)) {
            ScheduledSwitch row = read(document);
            if (row != null) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public void delete(String id) {
        rows.deleteOne(new Document("_id", id));
    }

    private void ensureIndex() {
        if (indexed) {
            return;
        }
        synchronized (this) {
            if (!indexed) {
                rows.createIndex(Indexes.ascending("dueAt"));
                indexed = true;
            }
        }
    }

    private static Document document(ScheduledSwitch row) {
        return new Document("_id", row.id())
                .append("instanceId", row.instanceId().toString())
                .append("applianceName", row.applianceName())
                .append("on", row.on())
                .append("dueAt", Date.from(row.dueAt()))
                .append("subject", row.subject());
    }

    private static ScheduledSwitch read(Document document) {
        String id = document.getString("_id");
        String instanceId = document.getString("instanceId");
        String applianceName = document.getString("applianceName");
        Date dueAt = document.getDate("dueAt");
        String subject = document.getString("subject");
        if (id == null || instanceId == null || applianceName == null || dueAt == null || subject == null) {
            return null;
        }
        if (!document.containsKey("on") || !(document.get("on") instanceof Boolean on)) {
            return null;
        }
        try {
            return new ScheduledSwitch(id, UUID.fromString(instanceId), applianceName, on, dueAt.toInstant(), subject);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
