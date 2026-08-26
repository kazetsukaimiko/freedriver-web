package io.freedriver.mqtt.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApplianceJsonTest {

    private static final UUID INSTANCE = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void topicA_happyPath_bodyHasNoInstanceId() {
        ApplianceStateMessage message = ApplianceJson.readState("""
                {
                  "instanceName": "Cabin",
                  "appliedCommandId": "550e8400-e29b-41d4-a716-446655440000",
                  "appliances": [
                    {"applianceName": "hallway", "state": true}
                  ]
                }
                """);
        assertEquals("Cabin", message.instanceName());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", message.appliedCommandId());
        assertEquals("hallway", message.appliances().getFirst().applianceName());
        assertTrue(message.appliances().getFirst().state());
        assertEquals(message, ApplianceJson.readState(ApplianceJson.writeState(message)));
        assertEquals(
                "freedriver/v1/" + INSTANCE + "/appliances",
                ApplianceSchemas.appliancesTopic(INSTANCE));
        assertFalse(ApplianceJson.writeState(message).contains("instanceId"));
    }

    @Test
    void topicB_happyPath_bodyHasNoInstanceId() {
        ApplianceCommandMessage command = ApplianceJson.readCommand("""
                {
                  "commandId": "550e8400-e29b-41d4-a716-446655440000",
                  "applianceName": "hallway",
                  "state": false
                }
                """);
        assertEquals("550e8400-e29b-41d4-a716-446655440000", command.commandId());
        assertEquals("hallway", command.applianceName());
        assertFalse(command.state());
        assertEquals(command, ApplianceJson.readCommand(ApplianceJson.writeCommand(command)));
        assertEquals(
                "freedriver/v1/" + INSTANCE + "/commands",
                ApplianceSchemas.commandsTopic(INSTANCE));
        assertFalse(ApplianceJson.writeCommand(command).contains("instanceId"));
    }

    @Test
    void topicA_rejectsInstanceIdInBody() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readState("""
                {"instanceId":"550e8400-e29b-41d4-a716-446655440000","instanceName":"Cabin","appliedCommandId":null,"appliances":[]}
                """));
    }

    @Test
    void topicB_rejectsInstanceIdInBody() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommand("""
                {"instanceId":"550e8400-e29b-41d4-a716-446655440000","commandId":"cmd-1","applianceName":"hallway","state":false}
                """));
    }

    @Test
    void topicA_rejectsOnField() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readState("""
                {"instanceName":"Cabin","appliedCommandId":null,"appliances":[{"applianceName":"hallway","on":true}]}
                """));
    }

    @Test
    void topicB_rejectsOnField() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommand("""
                {"commandId":"cmd-1","applianceName":"hallway","on":false}
                """));
    }

    @Test
    void topicA_rejectsExtraFields() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readState("""
                {"instanceName":"Cabin","appliedCommandId":null,"appliances":[],"nope":true}
                """));
    }

    @Test
    void topicB_rejectsExtraFields() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommand("""
                {"commandId":"cmd-1","applianceName":"hallway","state":false,"retain":true}
                """));
    }

    @Test
    void topicA_rejectsNameInsteadOfApplianceName() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readState("""
                {"instanceName":"Cabin","appliedCommandId":null,"appliances":[{"name":"hallway","state":true}]}
                """));
    }

    @Test
    void topicA_allowsNullAppliedCommandId() {
        ApplianceStateMessage message = ApplianceJson.readState("""
                {"instanceName":"Cabin","appliedCommandId":null,"appliances":[{"applianceName":"hallway","state":true}]}
                """);
        assertNull(message.appliedCommandId());
        assertEquals(List.of(new Appliance("hallway", true)), message.appliances());
    }

    @Test
    void topicA_rejectsBlankInstanceName() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readState("""
                {"instanceName":"  ","appliedCommandId":null,"appliances":[]}
                """));
    }

    @Test
    void topicB_rejectsMissingState() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommand("""
                {"commandId":"cmd-1","applianceName":"hallway"}
                """));
    }

    @Test
    void topicA_rejectsSchemaVersion() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readState("""
                {"schemaVersion":2,"instanceName":"Cabin","appliedCommandId":null,"appliances":[]}
                """));
    }

    @Test
    void topicHelpers_keepInstanceIdOffTheName() {
        String topic = ApplianceSchemas.appliancesTopic(INSTANCE);
        assertFalse(topic.contains("Cabin"));
        assertTrue(topic.contains(INSTANCE.toString()));
        assertEquals(1, ApplianceSchemas.QOS);
        assertFalse(ApplianceSchemas.RETAIN);
    }

}
