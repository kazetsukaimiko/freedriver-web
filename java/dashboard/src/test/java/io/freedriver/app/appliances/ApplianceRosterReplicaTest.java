package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The roster repository on the Dev Services Mongo container, also from a second instance. */
@QuarkusTest
class ApplianceRosterReplicaTest {

    @Inject
    ApplianceRoster roster;

    @Test
    void a_house_row_is_written_read_replaced_and_removed() {
        UUID id = UUID.randomUUID();
        ApplianceSnapshot first = snapshot(new Appliance("hallway", true));

        roster.save(id, "Cabin", first);
        assertEquals(new ApplianceRoster.Row("Cabin", first), roster.find(id).orElseThrow());
        assertEquals(new ApplianceRoster.Row("Cabin", first), roster.list().get(id));

        ApplianceSnapshot second = snapshot(new Appliance("hallway", false), new Appliance("fridge", true));
        roster.save(id, "Cabin", second);
        assertEquals(new ApplianceRoster.Row("Cabin", second), roster.find(id).orElseThrow());

        roster.deleteById(id.toString());
        assertTrue(roster.find(id).isEmpty());
        assertFalse(roster.list().containsKey(id));
    }

    @Test
    void a_second_replica_reads_the_first_replicas_row() {
        ApplianceRoster other = new ApplianceRoster();
        UUID id = UUID.randomUUID();
        ApplianceSnapshot snapshot = snapshot(new Appliance("porch", true));

        roster.save(id, "Van", snapshot);
        assertEquals(new ApplianceRoster.Row("Van", snapshot), other.find(id).orElseThrow());
        other.deleteById(id.toString());
        assertTrue(roster.find(id).isEmpty());
    }

    private static ApplianceSnapshot snapshot(Appliance... appliances) {
        return new ApplianceSnapshot(Instant.now().truncatedTo(ChronoUnit.MILLIS), List.of(appliances));
    }
}
