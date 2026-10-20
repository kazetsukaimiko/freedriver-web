package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MockAutonomyTest {

    private static final UUID SHOP_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Inject
    MockAutonomy mock;

    @Inject
    ApplianceControl control;

    @Inject
    Event<ApplianceCommandRouted> commands;

    @BeforeEach
    void reset() {
        control.forget(SHOP_ID);
        mock.reset();
    }

    @AfterEach
    void dropShop() {
        control.forget(SHOP_ID);
    }

    @Test
    void seeds_six_named_appliances_on_one_instance() {
        ApplianceSnapshot snapshot = control.instanceSnapshot(MockAutonomy.INSTANCE_ID).orElseThrow();
        List<String> names = snapshot.appliances().stream().map(a -> a.applianceName()).toList();
        assertEquals(MockAutonomy.FIXTURE_NAMES, names);
        assertEquals("Cabin", control.instanceName(MockAutonomy.INSTANCE_ID).orElseThrow());
    }

    @Test
    void command_on_the_bus_flips_state() {
        commands.fire(new ApplianceCommandRouted(
                MockAutonomy.INSTANCE_ID, new ApplianceCommandMessage("cmd-1", "kitchen", true)));
        assertTrue(control.instanceSnapshot(MockAutonomy.INSTANCE_ID).orElseThrow().find("kitchen").orElseThrow().state());
    }

    @Test
    void unknown_name_does_not_grow_the_house() {
        commands.fire(new ApplianceCommandRouted(
                MockAutonomy.INSTANCE_ID, new ApplianceCommandMessage("cmd-2", "attic", true)));
        assertEquals(6, control.instanceSnapshot(MockAutonomy.INSTANCE_ID).orElseThrow().appliances().size());
        assertTrue(control.instanceSnapshot(MockAutonomy.INSTANCE_ID).orElseThrow().find("attic").isEmpty());
    }

    @Test
    void other_instance_commands_are_ignored() {
        UUID other = UUID.fromString("11111111-1111-4111-8111-111111111111");
        commands.fire(new ApplianceCommandRouted(other, new ApplianceCommandMessage("cmd-3", "hallway", true)));
        assertEquals(
                false,
                control.instanceSnapshot(MockAutonomy.INSTANCE_ID).orElseThrow().find("hallway").orElseThrow().state());
        assertTrue(control.instanceSnapshot(other).isEmpty());
        assertFalse(control.allKnownInstances().containsKey(other));
    }

    @Test
    void two_seeded_instances_both_appear_via_control() {
        mock.seedInstance(SHOP_ID, "Shop", MockAutonomy.FIXTURE_NAMES);

        assertEquals("Cabin", control.instanceName(MockAutonomy.INSTANCE_ID).orElseThrow());
        assertEquals("Shop", control.instanceName(SHOP_ID).orElseThrow());
        assertEquals(names(MockAutonomy.INSTANCE_ID), MockAutonomy.FIXTURE_NAMES);
        assertEquals(names(SHOP_ID), MockAutonomy.FIXTURE_NAMES);
        assertEquals(2, control.allKnownInstances().size());
    }

    @Test
    void command_to_one_instance_does_not_flip_the_other() {
        mock.seedInstance(SHOP_ID, "Shop", MockAutonomy.FIXTURE_NAMES);

        assertTrue(control.publishCommand(
                MockAutonomy.INSTANCE_ID, new ApplianceCommandMessage("cmd-a", "kitchen", true)));
        assertTrue(applianceOn(MockAutonomy.INSTANCE_ID, "kitchen"));
        assertFalse(applianceOn(SHOP_ID, "kitchen"));

        assertTrue(control.publishCommand(
                SHOP_ID, new ApplianceCommandMessage("cmd-b", "porch", true)));
        assertTrue(applianceOn(SHOP_ID, "porch"));
        assertFalse(applianceOn(MockAutonomy.INSTANCE_ID, "porch"));
        assertTrue(applianceOn(MockAutonomy.INSTANCE_ID, "kitchen"));
    }

    private List<String> names(UUID instanceId) {
        return control.instanceSnapshot(instanceId).orElseThrow().appliances().stream()
                .map(appliance -> appliance.applianceName())
                .toList();
    }

    private boolean applianceOn(UUID instanceId, String applianceName) {
        return control.instanceSnapshot(instanceId).orElseThrow().find(applianceName).orElseThrow().state();
    }
}
