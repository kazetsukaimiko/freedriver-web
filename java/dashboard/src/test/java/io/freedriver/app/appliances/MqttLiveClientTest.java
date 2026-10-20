package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(MqttLiveClientTest.LiveProfile.class)
class MqttLiveClientTest {

    private static final UUID SILENT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID UNKNOWN = UUID.fromString("22222222-2222-4222-8222-222222222222");

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
                    "freedriver.mqtt.instance-ids", MockAutonomy.INSTANCE_ID + "," + SILENT,
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
        control.forget(SILENT);
        control.forget(UNKNOWN);
        client.resetTracking();
    }

    @Test
    void client_is_started_and_subscribes_exact_topic() {
        assertTrue(client != null);
        assertTrue(mqtt.connected());
        assertEquals(
                Set.of(
                        ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                        ApplianceSchemas.appliancesTopic(SILENT)),
                Set.copyOf(mqtt.subscriptions()));
        for (String topic : mqtt.subscriptions()) {
            assertFalse(topic.contains("+"), topic);
            assertFalse(topic.contains("#"), topic);
        }
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> "mqtt-live".equals(thread.getName())));
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

    @Test
    void state_summary_counts_each_configured_instance() {
        assertEquals("never", MqttLiveClient.lastHeardAgo(Instant.parse("2026-10-20T00:00:12Z"), -1));
        assertEquals("12s", MqttLiveClient.lastHeardAgo(Instant.ofEpochMilli(12_000), 0));
        assertEquals("0s", MqttLiveClient.lastHeardAgo(Instant.ofEpochMilli(500), 1_000));

        try (MqttLogCapture logs = MqttLogCapture.open()) {
            mqtt.publishState(
                    ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                    ApplianceJson.writeState(new ApplianceStateMessage(
                            "Cabin",
                            null,
                            List.of(new Appliance("hallway", true), new Appliance("kitchen", false)))));
            String received = logs.find("mqtt state received");
            assertTrue(received.contains("instance=" + MockAutonomy.INSTANCE_ID), received);
            assertTrue(received.contains("appliances=2"), received);
            assertFalse(received.contains("hallway"), received);
            assertEquals(500, logs.levelOf("mqtt state received"));

            client.logStateSummary(Instant.now().plusSeconds(3));
            String cabin = logs.find("mqtt state summary instance=" + MockAutonomy.INSTANCE_ID);
            String silent = logs.find("mqtt state summary instance=" + SILENT);
            assertTrue(cabin.contains("messages=1"), cabin);
            assertTrue(cabin.contains("lastHeardAgo=3s") || cabin.contains("lastHeardAgo=4s"), cabin);
            assertTrue(silent.contains("messages=0"), silent);
            assertTrue(silent.contains("lastHeardAgo=never"), silent);
            assertEquals(800, logs.levelOf("mqtt state summary"));
        }
    }

    @Test
    void rejected_state_logs_topic_and_reason_without_payload_or_exception_text() {
        String marker = "PAYLOAD_MARKER";
        String bad = marker + " {not-json";
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            client.onBrokerMessage(
                    ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                    bad.getBytes(StandardCharsets.UTF_8));
            client.onBrokerMessage(
                    "freedriver/v1/not-a-uuid/appliances",
                    bad.getBytes(StandardCharsets.UTF_8));
            client.onBrokerMessage(
                    ApplianceSchemas.appliancesTopic(UNKNOWN),
                    ApplianceJson.writeState(new ApplianceStateMessage(
                            marker, null, List.of(new Appliance("hallway", true)))).getBytes(StandardCharsets.UTF_8));

            String schema = logs.find("reason=schema");
            assertTrue(schema.contains("topic=" + ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID)), schema);
            assertTrue(schema.startsWith("mqtt state rejected "), schema);
            String malformed = logs.find("topic=freedriver/v1/not-a-uuid/appliances");
            assertTrue(malformed.contains("reason=unknown-instance"), malformed);
            String unknown = logs.find("topic=" + ApplianceSchemas.appliancesTopic(UNKNOWN));
            assertTrue(unknown.contains("reason=unknown-instance"), unknown);
            assertEquals(java.util.logging.Level.WARNING.intValue(), logs.levelOf("reason=schema"));
            String all = logs.all();
            assertFalse(all.contains(marker), all);
            assertFalse(all.contains("not-json"), all);
            assertFalse(all.contains("Exception"), all);
            assertTrue(control.instanceSnapshot(UNKNOWN).isEmpty());
            assertTrue(control.instanceSnapshot(MockAutonomy.INSTANCE_ID).isEmpty());
        }
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void command_sent_logs_the_ask_and_warns_when_acknowledgement_misses_command_timeout() throws Exception {
        seedCabin(false);
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            ApplianceCommandMessage command = new ApplianceCommandMessage("cmd-log-timeout", "hallway", true);
            assertTrue(control.publishCommand(MockAutonomy.INSTANCE_ID, command));
            String sent = logs.await("mqtt command sent", 1000);
            assertTrue(sent.contains("commandId=cmd-log-timeout"), sent);
            assertTrue(sent.contains("instance=" + MockAutonomy.INSTANCE_ID), sent);
            assertTrue(sent.contains("appliance=hallway"), sent);
            assertTrue(sent.contains("state=true"), sent);
            assertTrue(sent.contains("user=kaze"), sent);
            assertFalse(sent.contains("{"), sent);
            assertEquals(800, logs.levelOf("mqtt command sent"));

            String warn = logs.await("mqtt command unacknowledged", 2000);
            assertTrue(warn.contains("commandId=cmd-log-timeout"), warn);
            assertTrue(warn.contains("instance=" + MockAutonomy.INSTANCE_ID), warn);
            assertTrue(warn.contains("appliance=hallway"), warn);
            assertTrue(warn.contains("state=true"), warn);
            assertTrue(warn.contains("user=kaze"), warn);
            assertTrue(warn.contains("timeoutMs=200"), warn);
            assertEquals(java.util.logging.Level.WARNING.intValue(), logs.levelOf("mqtt command unacknowledged"));
            assertFalse(warn.contains("{"), warn);
        }
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void acknowledged_command_does_not_log_a_timeout() throws Exception {
        seedCabin(false);
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            assertTrue(control.publishCommand(
                    MockAutonomy.INSTANCE_ID, new ApplianceCommandMessage("cmd-log-ack", "hallway", true)));
            mqtt.publishState(
                    ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                    ApplianceJson.writeState(new ApplianceStateMessage(
                            "Cabin", "cmd-log-ack", List.of(new Appliance("hallway", true)))));
            logs.await("mqtt command sent", 1000);
            Thread.sleep(700);
            assertFalse(logs.all().contains("mqtt command unacknowledged"), logs.all());
            assertTrue(logs.all().contains("user=kaze"), logs.all());
        }
    }

    private void seedCabin(boolean on) {
        mqtt.publishState(
                ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                ApplianceJson.writeState(new ApplianceStateMessage(
                        "Cabin", null, List.of(new Appliance("hallway", on)))));
    }
}
