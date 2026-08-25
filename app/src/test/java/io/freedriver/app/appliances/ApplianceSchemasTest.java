package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;
import io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceSchemasTest {

    private static final UUID INSTANCE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void state_rejects_extra_fields() {
        String json = """
                {"instanceId":"550e8400-e29b-41d4-a716-446655440000","instanceName":"Cabin","appliedCommandId":null,"appliances":[{"applianceName":"living-room-lamp","on":true}],"nope":true}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse(json));
    }

    @Test
    void state_rejects_name_field() {
        String json = """
                {"instanceId":"550e8400-e29b-41d4-a716-446655440000","instanceName":"Cabin","appliedCommandId":null,"appliances":[{"name":"living-room-lamp","on":true}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse(json));
    }

    @Test
    void state_rejects_schema_version_field() {
        String json = """
                {"schemaVersion":1,"instanceId":"550e8400-e29b-41d4-a716-446655440000","instanceName":"Cabin","appliedCommandId":null,"appliances":[]}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse(json));
    }

    @Test
    void command_round_trip() {
        ApplianceCommandMessage command = new ApplianceCommandMessage(
                INSTANCE_ID, "cmd-1", "living-room-lamp", false);
        ApplianceCommandMessage parsed = ApplianceCommandMessage.parse(command.toJson());
        assertEquals(command, parsed);
        assertEquals(INSTANCE_ID, parsed.instanceId());
        assertEquals("living-room-lamp", parsed.applianceName());
        assertEquals(
                "freedriver/v1/550e8400-e29b-41d4-a716-446655440000/commands",
                ApplianceSchemas.commandsTopic(INSTANCE_ID));
        assertEquals("freedriver/v1/{instanceId}/commands", ApplianceSchemas.COMMANDS_TOPIC_TEMPLATE);
        assertEquals("freedriver/v1/{instanceId}/appliances", ApplianceSchemas.APPLIANCES_TOPIC_TEMPLATE);
        assertEquals(1, ApplianceSchemas.QOS);
        assertFalse(ApplianceSchemas.RETAIN);
    }

    @Test
    void command_rejects_extra_fields() {
        String json = """
                {"instanceId":"550e8400-e29b-41d4-a716-446655440000","commandId":"cmd-1","applianceName":"living-room-lamp","on":false,"retain":true}
                """;
        assertThrows(IllegalArgumentException.class, () -> ApplianceCommandMessage.parse(json));
    }

    @Test
    void command_rejects_name_field() {
        String json = """
                {"instanceId":"550e8400-e29b-41d4-a716-446655440000","commandId":"cmd-1","name":"living-room-lamp","on":false}
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
        ApplianceCommandMessage command = MqttLiveRoute.command(INSTANCE_ID, "cmd-1", "living-room-lamp", true);
        assertEquals(INSTANCE_ID, command.instanceId());
        assertEquals("living-room-lamp", command.applianceName());
        assertTrue(command.on());
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
