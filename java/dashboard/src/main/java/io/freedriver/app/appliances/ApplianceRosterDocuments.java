package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** One {@code appliance-roster} document. {@code _id} is the instance id. */
final class ApplianceRosterDocuments {

    static final String COLLECTION = "appliance-roster";

    private ApplianceRosterDocuments() {
    }

    static Document document(UUID instanceId, String instanceName, ApplianceSnapshot snapshot) {
        List<Document> appliances = new ArrayList<>();
        for (Appliance appliance : snapshot.appliances()) {
            appliances.add(new Document("applianceName", appliance.applianceName()).append("state", appliance.state()));
        }
        Document row = new Document("_id", instanceId.toString())
                .append("instanceName", instanceName)
                .append("appliances", appliances);
        if (snapshot.receivedAt() != null) {
            row.append("receivedAt", Date.from(snapshot.receivedAt()));
        }
        return row;
    }

    static ApplianceRoster.Row read(Document document) {
        Date received = document.getDate("receivedAt");
        Instant when = received == null ? null : received.toInstant();
        List<Appliance> appliances = new ArrayList<>();
        List<Document> stored = document.getList("appliances", Document.class);
        if (stored != null) {
            for (Document appliance : stored) {
                appliances.add(new Appliance(
                        appliance.getString("applianceName"),
                        Boolean.TRUE.equals(appliance.getBoolean("state"))));
            }
        }
        return new ApplianceRoster.Row(document.getString("instanceName"), new ApplianceSnapshot(when, appliances));
    }
}
