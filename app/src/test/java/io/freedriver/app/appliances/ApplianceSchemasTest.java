package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;
import io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceSchemasTest {

    @Test
    void state_rejects_extra_fields() {
        String json = """
                {"schemaVersion":1,"appliedCommandId":null,"appliances":[{"name":"living-room-lamp","on":true}],"nope":true}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse(json));
    }

    @Test
    void state_rejects_id_field() {
        String json = """
                {"schemaVersion":1,"appliedCommandId":null,"appliances":[{"id":"living-room-lamp","name":"living-room-lamp","on":true}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse(json));
    }

    @Test
    void state_rejects_other_schema_version() {
        String json = """
                {"schemaVersion":2,"appliedCommandId":null,"appliances":[]}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse(json));
    }

    @Test
    void command_round_trip() {
        ApplianceCommandMessage command = new ApplianceCommandMessage(1, "cmd-1", "living-room-lamp", false);
        ApplianceCommandMessage parsed = ApplianceCommandMessage.parse(command.toJson());
        assertEquals(command, parsed);
        assertEquals("living-room-lamp", parsed.name());
        assertEquals(ApplianceSchemas.COMMAND_TOPIC, "freedriver/v1/home/commands");
        assertEquals(1, ApplianceSchemas.QOS);
        assertFalse(ApplianceSchemas.RETAIN);
    }

    @Test
    void command_rejects_extra_fields() {
        String json = """
                {"schemaVersion":1,"commandId":"cmd-1","name":"living-room-lamp","on":false,"retain":true}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceCommandMessage.parse(json));
    }

    @Test
    void command_rejects_applianceId_field() {
        String json = """
                {"schemaVersion":1,"commandId":"cmd-1","applianceId":"living-room-lamp","name":"living-room-lamp","on":false}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceCommandMessage.parse(json));
    }

    @Test
    void mqtt_route_refuses_public_hostname() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> MqttLiveRoute.assertPrivateBroker("mqtt.freedriver.io"));
        assertTrue(ex.getMessage().contains("mqtt.freedriver.io"));
        MqttLiveRoute.assertPrivateBroker("mosquitto");
        ApplianceCommandMessage command = MqttLiveRoute.command("cmd-1", "living-room-lamp", true);
        assertEquals("living-room-lamp", command.name());
        assertTrue(command.on());
    }

    @Test
    void opposite_acls() {
        assertTrue(MqttAcl.canPublish(MqttAcl.Identity.AUTONOMY, ApplianceSchemas.STATE_TOPIC, false));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.AUTONOMY, ApplianceSchemas.STATE_TOPIC, true));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.AUTONOMY, ApplianceSchemas.COMMAND_TOPIC, false));
        assertTrue(MqttAcl.canSubscribe(MqttAcl.Identity.AUTONOMY, ApplianceSchemas.COMMAND_TOPIC));
        assertFalse(MqttAcl.canSubscribe(MqttAcl.Identity.AUTONOMY, ApplianceSchemas.STATE_TOPIC));

        assertTrue(MqttAcl.canPublish(MqttAcl.Identity.API, ApplianceSchemas.COMMAND_TOPIC, false));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.API, ApplianceSchemas.COMMAND_TOPIC, true));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.API, ApplianceSchemas.STATE_TOPIC, false));
        assertTrue(MqttAcl.canSubscribe(MqttAcl.Identity.API, ApplianceSchemas.STATE_TOPIC));
        assertFalse(MqttAcl.canSubscribe(MqttAcl.Identity.API, ApplianceSchemas.COMMAND_TOPIC));

        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.ANONYMOUS, ApplianceSchemas.STATE_TOPIC, false));
        assertFalse(MqttAcl.canPublish(MqttAcl.Identity.ANONYMOUS, ApplianceSchemas.COMMAND_TOPIC, false));
        assertFalse(MqttAcl.canSubscribe(MqttAcl.Identity.OTHER, ApplianceSchemas.STATE_TOPIC));
    }
}
