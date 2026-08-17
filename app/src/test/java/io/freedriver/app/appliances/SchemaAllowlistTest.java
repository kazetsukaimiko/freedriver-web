package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaAllowlistTest {

    @Test
    void topicA_parsesAndRejectsExtra() {
        ApplianceStateMessage message = ApplianceStateMessage.parse("""
                {
                  "schemaVersion": 1,
                  "appliedCommandId": null,
                  "appliances": [
                    {"id": "living-room-lamp", "name": "Living room lamp", "on": true}
                  ]
                }
                """);
        assertEquals(1, message.schemaVersion());
        assertNull(message.appliedCommandId());
        assertEquals("living-room-lamp", message.appliances().get(0).id());
        assertTrue(message.appliances().get(0).on());
        assertEquals(ApplianceSchemas.STATE_TOPIC, "freedriver/v1/home/appliances");
        assertThrows(IllegalArgumentException.class, () -> ApplianceStateMessage.parse("""
                {"schemaVersion":1,"appliedCommandId":null,"appliances":[],"extra":true}
                """));
    }

    @Test
    void topicB_parsesAndRejectsExtra() {
        ApplianceCommandMessage command = ApplianceCommandMessage.parse("""
                {"schemaVersion":1,"commandId":"550e8400-e29b-41d4-a716-446655440000","applianceId":"living-room-lamp","on":false}
                """);
        assertEquals("living-room-lamp", command.applianceId());
        assertEquals(false, command.on());
        assertEquals(ApplianceSchemas.COMMAND_TOPIC, "freedriver/v1/home/commands");
        assertThrows(IllegalArgumentException.class, () -> ApplianceCommandMessage.parse("""
                {"schemaVersion":1,"commandId":"x","applianceId":"living-room-lamp","on":false,"retain":true}
                """));
    }

    @Test
    void restBody_rejectsCommandIdFromBrowser() {
        ApplianceCommandRequest request = new ApplianceCommandRequest();
        request.setOn(true);
        request.extra("commandId", "nope");
        assertThrows(Exception.class, request::assertValid);
    }
}
