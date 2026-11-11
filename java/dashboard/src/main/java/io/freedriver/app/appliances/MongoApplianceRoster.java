package io.freedriver.app.appliances;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

/** {@code appliance-roster} in the dashboard database. The document id is the instance id. */
@ApplicationScoped
class MongoApplianceRoster implements ApplianceRoster {

    private final MongoCollection<Document> instances;

    /** Arc builds the client proxy with this constructor. */
    MongoApplianceRoster() {
        this.instances = null;
    }

    @Inject
    MongoApplianceRoster(
            MongoClient client,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database) {
        this.instances = client.getDatabase(database).getCollection(ApplianceRosterDocuments.COLLECTION);
    }

    @Override
    public void save(UUID instanceId, String instanceName, ApplianceSnapshot snapshot) {
        instances.replaceOne(
                eq("_id", instanceId.toString()),
                ApplianceRosterDocuments.document(instanceId, instanceName, snapshot),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<Row> find(UUID instanceId) {
        Document document = instances.find(eq("_id", instanceId.toString())).first();
        return document == null ? Optional.empty() : Optional.of(ApplianceRosterDocuments.read(document));
    }

    @Override
    public Map<UUID, Row> list() {
        Map<UUID, Row> out = new LinkedHashMap<>();
        for (Document document : instances.find()) {
            String id = document.getString("_id");
            if (id == null) {
                continue;
            }
            out.put(UUID.fromString(id), ApplianceRosterDocuments.read(document));
        }
        return Map.copyOf(out);
    }

    @Override
    public void remove(UUID instanceId) {
        instances.deleteOne(eq("_id", instanceId.toString()));
    }
}
