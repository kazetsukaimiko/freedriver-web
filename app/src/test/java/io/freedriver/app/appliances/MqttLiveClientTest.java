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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(MqttLiveClientTest.LiveProfile.class)
class MqttLiveClientTest {

    public static class LiveProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.appliances.enabled", "true",
                    "freedriver.appliances.live-commands", "true",
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

    @BeforeEach
    void reset() {
        mqtt.reset();
        control.forget(MockAutonomy.INSTANCE_ID);
    }

    @Test
    void client_is_started_and_subscribes_exact_topic() {
        assertTrue(client != null);
        assertTrue(mqtt.connected());
        assertEquals(List.of(ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID)), mqtt.subscriptions());
        assertFalse(mqtt.subscriptions().getFirst().contains("+"));
        assertFalse(mqtt.subscriptions().getFirst().contains("#"));
    }

    @Test
    void fixture_state_lands_on_the_same_bus() {
        mqtt.publishState(
                ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                ApplianceJson.writeState(new ApplianceStateMessage(
                        "Cabin",
                        null,
                        List.of(new Appliance("hallway", true), new Appliance("kitchen", false)))));

        Optional<ApplianceSnapshot> snapshot = control.instanceSnapshot(MockAutonomy.INSTANCE_ID);
        assertTrue(snapshot.isPresent());
        assertEquals(2, snapshot.get().appliances().size());
        assertTrue(snapshot.get().find("hallway").orElseThrow().state());
        assertEquals("Cabin", control.instanceName(MockAutonomy.INSTANCE_ID).orElseThrow());
    }

    @Test
    void command_publishes_topic_b_retain_false_qos_1() {
        mqtt.publishState(
                ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                ApplianceJson.writeState(new ApplianceStateMessage(
                        "Cabin", null, List.of(new Appliance("hallway", false)))));

        ApplianceCommandMessage command = new ApplianceCommandMessage("cmd-live-1", "hallway", true);
        assertTrue(control.publishCommand(MockAutonomy.INSTANCE_ID, command));

        assertEquals(1, mqtt.published().size());
        FixtureMqttConnection.Published published = mqtt.published().getFirst();
        assertEquals(ApplianceSchemas.commandsTopic(MockAutonomy.INSTANCE_ID), published.topic());
        assertEquals(ApplianceSchemas.QOS, published.qos());
        assertFalse(published.retain());
        ApplianceCommandMessage wire = ApplianceJson.readCommand(published.payloadUtf8());
        assertEquals("cmd-live-1", wire.commandId());
        assertEquals("hallway", wire.applianceName());
        assertTrue(wire.state());
        assertFalse(published.payloadUtf8().contains("instanceId"));
    }

    @Test
    void handshake_applied_command_id_unblocks_waiter() {
        mqtt.publishState(
                ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                ApplianceJson.writeState(new ApplianceStateMessage(
                        "Cabin", null, List.of(new Appliance("hallway", false)))));

        Thread fixture = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            mqtt.publishState(
                    ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                    ApplianceJson.writeState(new ApplianceStateMessage(
                            "Cabin",
                            "cmd-live-2",
                            List.of(new Appliance("hallway", true)))));
        }, "mqtt-fixture");
        fixture.setDaemon(true);
        fixture.start();

        Optional<ApplianceSnapshot> confirmed = control.publishCommandAndWait(
                MockAutonomy.INSTANCE_ID,
                new ApplianceCommandMessage("cmd-live-2", "hallway", true),
                Duration.ofSeconds(2));
        assertTrue(confirmed.isPresent());
        assertTrue(confirmed.get().find("hallway").orElseThrow().state());
    }
}
