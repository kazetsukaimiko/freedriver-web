package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Instance ids open a state subscription. Commands stay off with live-commands. */
@QuarkusTest
@TestProfile(MqttStateWhenCommandsOffTest.StateOnly.class)
class MqttStateWhenCommandsOffTest {

    public static class StateOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.appliances.enabled", "true",
                    "freedriver.appliances.live-commands", "false",
                    "freedriver.appliances.mock", "false",
                    "freedriver.mqtt.host", "mosquitto",
                    "freedriver.mqtt.port", "8883",
                    "freedriver.mqtt.tls", "true",
                    "freedriver.mqtt.instance-ids", MockAutonomy.INSTANCE_ID.toString(),
                    "quarkus.http.test-port", "0");
        }
    }

    @Inject
    MqttLiveClient client;

    @Inject
    FixtureMqttConnection mqtt;

    @Inject
    ApplianceControl control;

    @Inject
    AppliancesConfig appliances;

    @BeforeEach
    void reset() {
        mqtt.reset();
        control.forget(MockAutonomy.INSTANCE_ID);
        client.resetTracking();
    }

    @Test
    void connects_and_subscribes_without_sending_commands() {
        assertFalse(appliances.liveCommands());
        assertTrue(mqtt.connected());
        assertEquals(Set.of(ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID)), Set.copyOf(mqtt.subscriptions()));
        for (String topic : mqtt.subscriptions()) {
            assertFalse(topic.contains("+"), topic);
            assertFalse(topic.contains("#"), topic);
        }

        mqtt.publishState(
                ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                ApplianceJson.writeState(new ApplianceStateMessage(
                        "Cabin", null, List.of(new Appliance("hallway", true)))));
        assertTrue(control.instanceSnapshot(MockAutonomy.INSTANCE_ID).orElseThrow().find("hallway").orElseThrow().state());

        assertTrue(control.publishCommand(
                MockAutonomy.INSTANCE_ID, new ApplianceCommandMessage("cmd-off", "hallway", false)));
        client.onCommand(
                new ApplianceCommandRouted(
                        MockAutonomy.INSTANCE_ID, new ApplianceCommandMessage("cmd-direct", "hallway", false)),
                "kaze");
        assertTrue(mqtt.published().isEmpty());
        assertFalse(client.acceptsCommands());
    }

    @Test
    void oversized_state_is_rejected_before_parsing() {
        byte[] oversized = new byte[MqttLiveClient.MAX_STATE_BYTES + 1];
        oversized[0] = '{';
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            client.onBrokerMessage(ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID), oversized);
            String rejected = logs.find("reason=size");
            assertTrue(rejected.contains("topic=" + ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID)), rejected);
            assertFalse(logs.all().contains("reason=schema"), logs.all());
        }
        assertTrue(control.instanceSnapshot(MockAutonomy.INSTANCE_ID).isEmpty());
        client.onBrokerMessage(
                ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                "{not-json".getBytes(StandardCharsets.UTF_8));
        assertTrue(control.instanceSnapshot(MockAutonomy.INSTANCE_ID).isEmpty());
    }
}
