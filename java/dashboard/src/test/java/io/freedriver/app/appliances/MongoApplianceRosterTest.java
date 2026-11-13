package io.freedriver.app.appliances;

import com.mongodb.client.MongoClient;
import io.freedriver.mqtt.contract.Appliance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production roster on the Dev Services Mongo container. */
@QuarkusTest
class MongoApplianceRosterTest {

    @Inject
    MongoApplianceRoster roster;

    @Inject
    MongoClient mongo;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

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

        roster.remove(id);
        assertTrue(roster.find(id).isEmpty());
        assertFalse(roster.list().containsKey(id));
    }

    @Test
    void a_second_replica_reads_the_first_replicas_row() {
        MongoApplianceRoster other = new MongoApplianceRoster(mongo, database);
        UUID id = UUID.randomUUID();
        ApplianceSnapshot snapshot = snapshot(new Appliance("porch", true));

        roster.save(id, "Van", snapshot);
        assertEquals(new ApplianceRoster.Row("Van", snapshot), other.find(id).orElseThrow());
        other.remove(id);
        assertTrue(roster.find(id).isEmpty());
    }

    private static ApplianceSnapshot snapshot(Appliance... appliances) {
        return new ApplianceSnapshot(Instant.now().truncatedTo(ChronoUnit.MILLIS), List.of(appliances));
    }
}
