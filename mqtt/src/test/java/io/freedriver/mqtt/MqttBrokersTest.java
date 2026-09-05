package io.freedriver.mqtt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MqttBrokersTest {

    @Test
    void live_broker_is_mosquitto_8883_tls() {
        MqttBrokers.assertLiveBroker("mosquitto", 8883, true);
        assertThrows(IllegalStateException.class, () -> MqttBrokers.assertPrivateBroker("mqtt.freedriver.io"));
        assertThrows(IllegalStateException.class, () -> MqttBrokers.assertLiveBroker("mosquitto", 1883, true));
        assertThrows(IllegalStateException.class, () -> MqttBrokers.assertLiveBroker("mosquitto", 8883, false));
        assertThrows(IllegalArgumentException.class, () -> MqttBrokers.assertExactTopic("freedriver/v1/+/appliances"));
        assertThrows(IllegalArgumentException.class, () -> MqttBrokers.assertExactTopic("freedriver/v1/#"));
        MqttBrokers.assertExactTopic("freedriver/v1/550e8400-e29b-41d4-a716-446655440000/appliances");
    }
}
