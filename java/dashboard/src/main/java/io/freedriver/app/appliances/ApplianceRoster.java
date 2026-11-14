package io.freedriver.app.appliances;

import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

/**
 * The last appliance snapshot for each instance, in {@code appliance-roster}.
 * The instance id names the row. Callers read the rows again after each write.
 */
@ApplicationScoped
public class ApplianceRoster implements PanacheMongoRepositoryBase<RosterRow, String> {

    void save(UUID instanceId, String instanceName, ApplianceSnapshot snapshot) {
        mongoCollection().replaceOne(eq("_id", instanceId.toString()), RosterRow.of(instanceId, instanceName, snapshot),
                new ReplaceOptions().upsert(true));
    }

    /** The stored row. Empty when that id has no row. */
    Optional<Row> find(UUID instanceId) {
        return findByIdOptional(instanceId.toString()).map(RosterRow::row);
    }

    Map<UUID, Row> list() {
        Map<UUID, Row> out = new LinkedHashMap<>();
        for (RosterRow row : listAll()) {
            if (row.id != null) {
                out.put(UUID.fromString(row.id), row.row());
            }
        }
        return Map.copyOf(out);
    }

    void remove(UUID instanceId) {
        deleteById(instanceId.toString());
    }

    public record Row(String instanceName, ApplianceSnapshot snapshot) {
    }
}
