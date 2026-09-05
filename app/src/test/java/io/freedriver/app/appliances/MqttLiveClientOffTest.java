package io.freedriver.app.appliances;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MqttLiveClientOffTest {

    @Inject
    FixtureMqttConnection mqtt;

    @Test
    void live_client_does_not_connect_when_live_commands_false() {
        assertFalse(mqtt.connected());
        assertTrue(mqtt.subscriptions().isEmpty());
    }
}
