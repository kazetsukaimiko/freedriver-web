package io.freedriver.app.appliances;

import io.freedriver.app.mqttlog.HouseMqttLog;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseMqttLogTest {

    private static final UUID INSTANCE = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void moved_lines_keep_text_level_and_hostile_inputs() {
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            HouseMqttLog.stateReceived(INSTANCE, 2);
            HouseMqttLog.stateSummary(INSTANCE, 4, "12s");
            HouseMqttLog.stateRejected("freedriver/v1/" + INSTANCE + "/appliances\nsecret", "schema");
            HouseMqttLog.metricsReceived(INSTANCE);
            HouseMqttLog.metricsRejected("freedriver/v1/" + INSTANCE + "/metrics\nsecret", "schema");
            HouseMqttLog.commandSent("cmd-1", INSTANCE, "hallway", true, "ka\nze");
            HouseMqttLog.commandUnacknowledged("cmd-1", INSTANCE, "hallway", true, "kaze", 5000);
            HouseMqttLog.commandFailed("cmd-1", INSTANCE);
            HouseMqttLog.house(INSTANCE, "created");
            HouseMqttLog.connectFailed();
            HouseMqttLog.configUnreadable("instance-ids\napi-pass");
            HouseMqttLog.instanceSubscription(INSTANCE, "subscribed");
            HouseMqttLog.instancesRefreshFailed();

            String all = logs.all();
            assertFalse(all.contains("\nsecret"), all);
            assertFalse(all.contains("password"), all);
            assertTrue(all.contains("mqtt state received instance=" + INSTANCE + " appliances=2"), all);
            assertTrue(all.contains("mqtt state summary instance=" + INSTANCE + " messages=4 lastHeardAgo=12s"), all);
            assertTrue(all.contains(
                    "mqtt state rejected topic=freedriver/v1/" + INSTANCE + "/appliancessecret reason=schema"), all);
            assertTrue(all.contains("mqtt metrics received instance=" + INSTANCE), all);
            assertTrue(all.contains(
                    "mqtt metrics rejected topic=freedriver/v1/" + INSTANCE + "/metricssecret reason=schema"), all);
            assertTrue(all.contains("mqtt command sent commandId=cmd-1 instance=" + INSTANCE
                    + " appliance=hallway state=true user=kaze"), all);
            assertTrue(all.contains("mqtt command unacknowledged commandId=cmd-1 instance=" + INSTANCE
                    + " appliance=hallway state=true user=kaze timeoutMs=5000"), all);
            assertTrue(all.contains("mqtt command failed commandId=cmd-1 instance=" + INSTANCE
                    + " reason=publish-failed"), all);
            assertTrue(all.contains("mqtt house instance=" + INSTANCE + " outcome=created"), all);
            assertTrue(all.contains("mqtt connect failed reason=connect-failed"), all);
            assertTrue(all.contains("mqtt config unreadable name=instance-idsapi-pass reason=read-failed"), all);
            assertTrue(all.contains("mqtt instance subscription instance=" + INSTANCE + " outcome=subscribed"), all);
            assertTrue(all.contains("mqtt instances refresh failed reason=list-failed"), all);

            assertEquals(500, logs.levelOf("mqtt state received"));
            assertEquals(500, logs.levelOf("mqtt metrics received"));
            assertEquals(800, logs.levelOf("mqtt state summary"));
            assertEquals(800, logs.levelOf("mqtt instance subscription"));
            assertEquals(800, logs.levelOf("mqtt command sent"));
            assertEquals(800, logs.levelOf("mqtt house instance="));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt connect failed"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt config unreadable"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt state rejected"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt instances refresh failed"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt metrics rejected"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt command unacknowledged"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt command failed"));
        }
    }

    @Test
    void refresh_failure_lines_name_the_reason_and_an_http_status() {
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            HouseMqttLog.instancesRefreshFailed("not-configured", OptionalInt.empty());
            HouseMqttLog.instancesRefreshFailed("group-missing", OptionalInt.empty());
            HouseMqttLog.instancesRefreshFailed("list-failed", OptionalInt.of(404));
            HouseMqttLog.instancesRefreshFailed("list-failed", OptionalInt.of(401));
            HouseMqttLog.instancesRefreshFailed("list-failed", OptionalInt.of(403));
            HouseMqttLog.instancesRefreshFailed("transport", OptionalInt.empty());
            HouseMqttLog.instancesRefreshFailed("list-failed\ntoken secret", OptionalInt.empty());

            String all = logs.all();
            assertTrue(all.contains("mqtt instances refresh failed reason=not-configured"), all);
            assertTrue(all.contains("mqtt instances refresh failed reason=group-missing"), all);
            assertTrue(all.contains("mqtt instances refresh failed reason=list-failed status=404"), all);
            assertTrue(all.contains("mqtt instances refresh failed reason=list-failed status=401"), all);
            assertTrue(all.contains("mqtt instances refresh failed reason=list-failed status=403"), all);
            assertTrue(all.contains("mqtt instances refresh failed reason=transport"), all);
            assertTrue(all.contains("mqtt instances refresh failed reason=list-failedtoken secret"), all);
            assertFalse(all.contains("\ntoken"), all);
            assertFalse(all.contains("reason=transport status="), all);
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt instances refresh failed reason=transport"));
        }
    }
}
