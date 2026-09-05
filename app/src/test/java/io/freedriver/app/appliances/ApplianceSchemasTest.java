package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceSchemasTest {

    private static final UUID INSTANCE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void mqtt_route_refuses_public_hostname() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> MqttLiveRoute.assertPrivateBroker("mqtt.freedriver.io"));
        assertTrue(ex.getMessage().contains("mqtt.freedriver.io"));
        MqttLiveRoute.assertPrivateBroker("mosquitto");
        MqttLiveRoute.assertLiveBroker("mosquitto", 8883, true);
        assertThrows(IllegalStateException.class, () -> MqttLiveRoute.assertLiveBroker("mosquitto", 1883, true));
        assertThrows(IllegalStateException.class, () -> MqttLiveRoute.assertLiveBroker("mosquitto", 8883, false));
        ApplianceCommandMessage command = MqttLiveRoute.command("cmd-1", "hallway", true);
        assertEquals("hallway", command.applianceName());
        assertTrue(command.state());
        assertFalse(ApplianceJson.writeCommand(command).contains("instanceId"));
    }

    @Test
    void opposite_acls() {
        String state = ApplianceSchemas.appliancesTopic(INSTANCE_ID);
        String commands = ApplianceSchemas.commandsTopic(INSTANCE_ID);

        assertTrue(MqttAcl.canPublish(MqttAcl.Identity.AUTONOMY, state, false));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.AUTONOMY, state, true));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.AUTONOMY, commands, false));
        assertTrue(MqttAcl.canSubscribe(MqttAcl.Identity.AUTONOMY, commands));
        assertFalse(MqttAcl.canSubscribe(MqttAcl.Identity.AUTONOMY, state));

        assertTrue(MqttAcl.canPublish(MqttAcl.Identity.API, commands, false));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.API, commands, true));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.API, state, false));
        assertTrue(MqttAcl.canSubscribe(MqttAcl.Identity.API, state));
        assertFalse(MqttAcl.canSubscribe(MqttAcl.Identity.API, commands));

        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.ANONYMOUS, state, false));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.ANONYMOUS, commands, false));
        assertFalse(MqttAcl.canSubscribe(MqttAcl.Identity.OTHER, state));
        assertFalse(MqttAcl.isAppliancesTopic("freedriver/v1/home/appliances"));
        assertFalse(MqttAcl.isCommandsTopic("freedriver/v1/Cabin/commands"));
    }
}
