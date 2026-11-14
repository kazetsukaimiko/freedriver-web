package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One instance's last snapshot in {@code appliance-roster}. {@code _id} is the instance id. */
@MongoEntity(collection = RosterRow.COLLECTION)
public class RosterRow {

    static final String COLLECTION = "appliance-roster";

    @BsonId
    public String id;
    public String instanceName;
    public List<StoredAppliance> appliances;
    public Instant receivedAt;

    /** One appliance as stored: its name and whether it is on. */
    public static class StoredAppliance {
        public String applianceName;
        public Boolean state;
    }

    static RosterRow of(UUID instanceId, String instanceName, ApplianceSnapshot snapshot) {
        RosterRow row = new RosterRow();
        row.id = instanceId.toString();
        row.instanceName = instanceName;
        row.receivedAt = snapshot.receivedAt();
        row.appliances = new ArrayList<>();
        for (Appliance appliance : snapshot.appliances()) {
            StoredAppliance stored = new StoredAppliance();
            stored.applianceName = appliance.applianceName();
            stored.state = appliance.state();
            row.appliances.add(stored);
        }
        return row;
    }

    ApplianceRoster.Row row() {
        List<Appliance> list = new ArrayList<>();
        if (appliances != null) {
            for (StoredAppliance stored : appliances) {
                list.add(new Appliance(stored.applianceName, Boolean.TRUE.equals(stored.state)));
            }
        }
        return new ApplianceRoster.Row(instanceName, new ApplianceSnapshot(receivedAt, list));
    }
}
