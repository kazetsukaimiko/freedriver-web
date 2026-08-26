package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.Appliance;
import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MockAutonomyTest {

    @Inject
    MockAutonomy mock;

    @Inject
    Event<ApplianceCommandMessage> commands;

    @BeforeEach
    void reset() {
        mock.reset();
    }

    @Test
    void seeds_exactly_six_named_appliances() {
        ApplianceSnapshot snapshot = mock.snapshot().orElseThrow();
        List<String> names = snapshot.appliances().stream().map(Appliance::applianceName).toList();
        assertEquals(MockAutonomy.FIXTURE_NAMES, names);
        assertEquals(6, names.size());
        assertTrue(snapshot.appliances().stream().noneMatch(Appliance::on));
        assertEquals(MockAutonomy.INSTANCE_ID, snapshot.instanceId());
        assertEquals(MockAutonomy.INSTANCE_NAME, snapshot.instanceName());
    }

    @Test
    void command_event_updates_store_via_mock() {
        commands.fire(new ApplianceCommandMessage(MockAutonomy.INSTANCE_ID, "cmd-1", "kitchen", true));

        ApplianceSnapshot snapshot = mock.snapshot().orElseThrow();
        assertEquals(1, mock.publishedCommands().size());
        assertEquals("cmd-1", snapshot.appliedCommandId());
        assertTrue(snapshot.find("kitchen").orElseThrow().on());
        assertEquals(6, snapshot.appliances().size());
    }

    @Test
    void no_confirm_leaves_map_unchanged() {
        mock.setConfirmCommands(false);

        commands.fire(new ApplianceCommandMessage(MockAutonomy.INSTANCE_ID, "cmd-2", "hallway", true));

        ApplianceSnapshot snapshot = mock.snapshot().orElseThrow();
        assertEquals(1, mock.publishedCommands().size());
        assertEquals(null, snapshot.appliedCommandId());
        assertFalse(snapshot.find("hallway").orElseThrow().on());
    }

    @Test
    void unknown_name_does_not_grow_the_house() {
        commands.fire(new ApplianceCommandMessage(MockAutonomy.INSTANCE_ID, "cmd-3", "attic", true));

        ApplianceSnapshot snapshot = mock.snapshot().orElseThrow();
        assertEquals(1, mock.publishedCommands().size());
        assertEquals(6, snapshot.appliances().size());
        assertTrue(snapshot.find("attic").isEmpty());
        assertEquals(null, snapshot.appliedCommandId());
    }
}
