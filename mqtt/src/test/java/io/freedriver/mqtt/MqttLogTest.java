package io.freedriver.mqtt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttLogTest {

    private static final UUID INSTANCE = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void logger_name_is_io_freedriver_mqtt() {
        assertEquals("io.freedriver.mqtt", MqttLog.NAME);
    }

    @Test
    void lines_are_one_record_and_name_no_secret() {
        try (LogLines logs = LogLines.open()) {
            MqttLog.connecting("mosquitto", 8883);
            MqttLog.connected("mosquitto", 8883, true);
            MqttLog.disconnected("bad\nreason");
            MqttLog.resubscribed(List.of("freedriver/v1/" + INSTANCE + "/appliances"));
            MqttLog.stateReceived(INSTANCE, 2);
            MqttLog.stateSummary(INSTANCE, 4, "12s");
            MqttLog.stateRejected("freedriver/v1/" + INSTANCE + "/appliances\nsecret", "schema");
            MqttLog.commandSent("cmd-1", INSTANCE, "hallway", true, "ka\nze");
            MqttLog.commandUnacknowledged("cmd-1", INSTANCE, "hallway", true, "kaze", 5000);
            MqttLog.commandFailed("cmd-1", INSTANCE);

            String all = logs.all();
            assertFalse(all.contains("\nsecret"), all);
            assertFalse(all.contains("password"), all);
            assertTrue(all.contains("mqtt connecting host=mosquitto port=8883"), all);
            assertTrue(all.contains("mqtt connected host=mosquitto port=8883 reconnect=true"), all);
            assertTrue(all.contains("mqtt disconnected reason=badreason"), all);
            assertTrue(all.contains("mqtt resubscribed topics=freedriver/v1/" + INSTANCE + "/appliances"), all);
            assertTrue(all.contains("mqtt state received instance=" + INSTANCE + " appliances=2"), all);
            assertTrue(all.contains("mqtt state summary instance=" + INSTANCE + " messages=4 lastHeardAgo=12s"), all);
            assertTrue(all.contains(
                    "mqtt state rejected topic=freedriver/v1/" + INSTANCE + "/appliancessecret reason=schema"), all);
            assertTrue(all.contains("mqtt command sent commandId=cmd-1 instance=" + INSTANCE
                    + " appliance=hallway state=true user=kaze"), all);
            assertTrue(all.contains("mqtt command unacknowledged commandId=cmd-1 instance=" + INSTANCE
                    + " appliance=hallway state=true user=kaze timeoutMs=5000"), all);
            assertTrue(all.contains("mqtt command failed commandId=cmd-1 instance=" + INSTANCE
                    + " reason=publish-failed"), all);
            assertEquals(500, logs.levelOf("mqtt state received"));
            assertEquals(800, logs.levelOf("mqtt command sent"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt state rejected"));
            assertEquals(Level.WARNING.intValue(), logs.levelOf("mqtt command unacknowledged"));
        }
    }
}
