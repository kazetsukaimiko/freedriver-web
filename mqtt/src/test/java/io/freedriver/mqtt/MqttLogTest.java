package io.freedriver.mqtt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttLogTest {

    private static final UUID INSTANCE = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void logger_name_is_io_freedriver_mqtt() {
        assertEquals("io.freedriver.mqtt", MqttLog.NAME);
    }

    @Test
    void connection_lines_stay_on_mqtt_log() {
        try (LogLines logs = LogLines.open()) {
            MqttLog.connecting("mosquitto", 8883);
            MqttLog.connected("mosquitto", 8883, true);
            MqttLog.disconnected("bad\nreason");
            MqttLog.resubscribed(List.of("freedriver/v1/" + INSTANCE + "/appliances"));
            MqttLog.resubscribeFailed("freedriver/v1/" + INSTANCE + "/appliances");

            String all = logs.all();
            assertTrue(all.contains("mqtt connecting host=mosquitto port=8883"), all);
            assertTrue(all.contains("mqtt connected host=mosquitto port=8883 reconnect=true"), all);
            assertTrue(all.contains("mqtt disconnected reason=badreason"), all);
            assertTrue(all.contains("mqtt resubscribed topics=freedriver/v1/" + INSTANCE + "/appliances"), all);
            assertTrue(all.contains("mqtt resubscribe failed topic=freedriver/v1/" + INSTANCE
                    + "/appliances reason=subscribe-failed"), all);
            assertEquals(800, logs.levelOf("mqtt connecting"));
            assertEquals(java.util.logging.Level.WARNING.intValue(), logs.levelOf("mqtt resubscribe failed"));
        }
    }
}
