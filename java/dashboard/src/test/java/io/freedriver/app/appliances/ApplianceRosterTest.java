package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The roster on the Dev Services Mongo container, shared by two routers as two replicas would share it. */
@QuarkusTest
class ApplianceRosterTest {

    @Inject
    ApplianceRoster roster;

    @Test
    void an_instance_recorded_on_one_process_is_listed_on_another() {
        ApplianceControl first = new ApplianceControl(roster);
        ApplianceControl second = new ApplianceControl(roster);
        UUID id = UUID.randomUUID();

        first.onState(new ApplianceStateRouted(
                id,
                new ApplianceStateMessage("Cabin", null, List.of(new Appliance("hallway", true)))));

        ApplianceRoster.Row listed = second.instances().get(id);
        assertEquals(List.of(new Appliance("hallway", true)), listed.snapshot().appliances());
        assertEquals("Cabin", listed.instanceName());
        roster.deleteById(id.toString());
        assertFalse(first.instances().containsKey(id));
    }

    @Test
    void an_instance_is_one_document_whose_id_is_the_instance() {
        Instant when = Instant.parse("2026-08-26T00:00:00Z");
        UUID id = UUID.randomUUID();
        roster.save(id, "Cabin", new ApplianceSnapshot(when, List.of(new Appliance("hallway", false))));

        Document row = roster.mongoCollection().withDocumentClass(Document.class).find(eq("_id", id.toString())).first();
        assertEquals("appliance-roster", roster.mongoCollection().getNamespace().getCollectionName());
        assertEquals("Cabin", row.getString("instanceName"));
        assertEquals(Date.from(when), row.getDate("receivedAt"));
        assertEquals(List.of(new Document("applianceName", "hallway").append("state", false)),
                row.getList("appliances", Document.class));
        roster.deleteById(id.toString());
    }
}
