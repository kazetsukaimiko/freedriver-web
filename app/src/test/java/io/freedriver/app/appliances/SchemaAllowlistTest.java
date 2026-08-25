package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;
import io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaAllowlistTest {

    private static final String INSTANCE_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void topicA_parsesAndRejectsExtra() {
        ApplianceStateMessage message = ApplianceStateMessage.parse("""
                {
                  "instanceId": "%s",
                  "instanceName": "Cabin",
                  "appliedCommandId": null,
                  "appliances": [
                    {"applianceName": "living-room-lamp", "on": true}
                  ]
                }
                """.formatted(INSTANCE_ID));
        assertEquals(UUID.fromString(INSTANCE_ID), message.instanceId());
        assertEquals("Cabin", message.instanceName());
        assertNull(message.appliedCommandId());
        assertEquals("living-room-lamp", message.appliances().get(0).applianceName());
        assertTrue(message.appliances().get(0).on());
        assertEquals(
                "freedriver/v1/" + INSTANCE_ID + "/appliances",
                ApplianceSchemas.appliancesTopic(message.instanceId()));
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse("""
                {"instanceId":"%s","instanceName":"Cabin","appliedCommandId":null,"appliances":[],"extra":true}
                """.formatted(INSTANCE_ID)));
    }

    @Test
    void topicB_parsesAndRejectsExtra() {
        ApplianceCommandMessage command = ApplianceCommandMessage.parse("""
                {"instanceId":"%s","commandId":"550e8400-e29b-41d4-a716-446655440000","applianceName":"living-room-lamp","on":false}
                """.formatted(INSTANCE_ID));
        assertEquals(UUID.fromString(INSTANCE_ID), command.instanceId());
        assertEquals("living-room-lamp", command.applianceName());
        assertEquals(false, command.on());
        assertEquals(
                "freedriver/v1/" + INSTANCE_ID + "/commands",
                ApplianceSchemas.commandsTopic(command.instanceId()));
        assertThrows(IllegalArgumentException.class, () -> ApplianceCommandMessage.parse("""
                {"instanceId":"%s","commandId":"x","applianceName":"living-room-lamp","on":false,"retain":true}
                """.formatted(INSTANCE_ID)));
    }

    @Test
    void restBody_rejectsCommandIdFromBrowser() {
        ApplianceCommandRequest request = new ApplianceCommandRequest();
        request.setOn(true);
        request.extra("commandId", "nope");
        assertThrows(Exception.class, request::assertValid);
    }
}
