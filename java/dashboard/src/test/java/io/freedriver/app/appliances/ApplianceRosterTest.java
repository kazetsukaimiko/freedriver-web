package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceRosterTest {

    @Test
    void an_instance_recorded_on_one_process_is_listed_on_another() {
        ApplianceRoster shared = new MemoryApplianceRoster();
        ApplianceControl first = new ApplianceControl(shared);
        ApplianceControl second = new ApplianceControl(shared);
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        first.onState(new ApplianceStateRouted(
                id,
                new ApplianceStateMessage("Cabin", null, List.of(new Appliance("hallway", true)))));

        Map<UUID, ApplianceSnapshot> listed = second.allKnownInstances();
        assertEquals(1, listed.size());
        assertEquals(List.of(new Appliance("hallway", true)), listed.get(id).appliances());
        assertEquals("Cabin", second.instanceNames().get(id));
        second.forget(id);
        assertTrue(first.allKnownInstances().isEmpty());
    }

    @Test
    void an_instance_is_one_document_whose_id_is_the_instance() {
        Instant when = Instant.parse("2026-08-26T00:00:00Z");
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Document row = ApplianceRosterDocuments.document(
                id,
                "Cabin",
                new ApplianceSnapshot(when, List.of(new Appliance("hallway", false))));

        assertEquals(id.toString(), row.get("_id"));
        assertEquals("Cabin", row.getString("instanceName"));
        assertEquals(Date.from(when), row.getDate("receivedAt"));
        assertEquals(ApplianceRosterDocuments.COLLECTION, "appliance-roster");

        ApplianceRoster.Row read = ApplianceRosterDocuments.read(row);
        assertEquals("Cabin", read.instanceName());
        assertEquals(when, read.snapshot().receivedAt());
        assertEquals(List.of(new Appliance("hallway", false)), read.snapshot().appliances());
    }
}
