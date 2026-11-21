package io.freedriver.mqtt.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.Deprecated;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;

import org.junit.jupiter.api.Test;

class ApplianceCommandListTest {

    private static final String ISSUED = "2026-11-19T15:04:05Z";
    private static final String LIST_ID = "00000000-0000-4000-8000-000000000001";
    private static final String ENTRY_ID = "00000000-0000-4000-8000-000000000002";
    private static final String ENTRY_TWO = "00000000-0000-4000-8000-000000000003";
    private static final String CANCEL_KEY = "00000000-0000-4000-8000-000000000004";
    private static final String ODD_UUID = "1-1-1-1-1";
    private static final String UPPER_UUID = "550E8400-E29B-41D4-A716-446655440000";
    private static final String LOWER_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CANCEL_ENTRY = "3f1c2b8e-6d4a-4f0e-9b7c-2a1d5e8f9c01";
    private static final String CANCEL_REF = "7a9e4d21-0c3b-4e5f-8a6d-1b2c3d4e5f60";

    @Test
    void topicsUseQosOneAndAreNotRetained() {
        UUID instance = UUID.fromString(LIST_ID);
        assertEquals("freedriver/v1/{instanceId}/command-lists", ApplianceSchemas.COMMAND_LISTS_TOPIC_TEMPLATE);
        assertEquals("freedriver/v1/" + instance + "/command-lists", ApplianceSchemas.commandListsTopic(instance));
        assertEquals(1, ApplianceSchemas.QOS);
        assertFalse(ApplianceSchemas.RETAIN);
        assertEquals(86400, ApplianceSchemas.MAX_DELAY_SECONDS);
        assertThrows(NullPointerException.class, () -> ApplianceSchemas.commandListsTopic(null));
    }

    @Test
    void listMapperDropsMissingCreatorCheckAndScalarCoercion() {
        assertTrue(ApplianceSchemas.STRICT.isEnabled(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES));
        assertTrue(ApplianceSchemas.STRICT.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertTrue(ApplianceSchemas.STRICT.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        assertFalse(ApplianceJson.LISTS.isEnabled(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES));
        assertTrue(ApplianceJson.LISTS.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertFalse(ApplianceJson.LISTS.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS));
    }

    @Test
    void roundTripApplianceCancelAndOnPlusDelayedOff() {
        ApplianceCommandList message = ApplianceJson.readCommandList(list(LIST_ID, """
                %s,
                %s,
                %s
                """.formatted(
                appliance(ENTRY_ID, "hallway", "true", null),
                appliance(ENTRY_TWO, "hallway", "false", "\"PT5M\""),
                cancelTarget(CANCEL_KEY, "porch"))));
        assertEquals(Instant.parse(ISSUED), message.issuedAt());
        assertEquals(3, message.commands().size());
        ApplianceCommandEntry now = (ApplianceCommandEntry) message.commands().get(0);
        assertEquals(CommandEntryType.APPLIANCE, now.type());
        assertEquals(Boolean.TRUE, now.state());
        assertEquals(null, now.delay());
        ApplianceCommandEntry later = (ApplianceCommandEntry) message.commands().get(1);
        assertEquals(Duration.parse("PT5M"), later.delay());
        CancelCommandEntry cancel = (CancelCommandEntry) message.commands().get(2);
        assertEquals(CommandEntryType.CANCEL, cancel.type());
        assertEquals("porch", cancel.target());
        assertEquals(null, cancel.cancelId());
        assertEquals(message, ApplianceJson.readCommandList(ApplianceJson.writeCommandList(message)));
        String written = ApplianceJson.writeCommandList(message);
        assertTrue(written.contains("\"type\":\"appliance\""));
        assertTrue(written.contains("\"type\":\"cancel\""));
        assertFalse(written.contains("cancelId"));
    }

    @Test
    void cancelByIdOmitsTargetStateAndDelay() {
        ApplianceCommandList message = ApplianceJson.readCommandList(
                list(LIST_ID, cancelId(ENTRY_ID, CANCEL_KEY)));
        CancelCommandEntry cancel = (CancelCommandEntry) message.commands().getFirst();
        assertEquals(CANCEL_KEY, cancel.cancelId());
        assertEquals(null, cancel.target());
        String written = ApplianceJson.writeCommandList(message);
        assertFalse(written.contains("target"));
        assertFalse(written.contains("state"));
        assertFalse(written.contains("delay"));
        assertEquals(message, ApplianceJson.readCommandList(written));
    }

    @Test
    void applianceWithoutDelayOmitsDelay() {
        ApplianceCommandList message = ApplianceJson.readCommandList(
                list(LIST_ID, appliance(ENTRY_ID, "hallway", "false", null)));
        assertEquals(null, ((ApplianceCommandEntry) message.commands().getFirst()).delay());
        assertFalse(ApplianceJson.writeCommandList(message).contains("delay"));
    }

    @Test
    void acceptsZeroAndInRangeDurationsInCanonicalForm() {
        assertEquals(Duration.ZERO, delayOf("\"PT0S\""));
        assertTrue(writeDelay("\"PT0S\"").contains("PT0S"));
        assertEquals(Duration.ZERO, delayOf("\"P0D\""));
        String fromDays = writeDelay("\"P0D\"");
        assertTrue(fromDays.contains("PT0S"));
        assertFalse(fromDays.contains("P0D"));
        assertEquals(Duration.ofSeconds(1), delayOf("\"PT1S\""));
        assertEquals(Duration.parse("PT1.5S"), delayOf("\"PT1.5S\""));
        assertTrue(writeDelay("\"PT1.5S\"").contains("PT1.5S"));
        assertEquals(Duration.ofHours(24), delayOf("\"PT24H\""));
        assertEquals(Duration.ofHours(24), delayOf("\"P1D\""));
        String fromDay = writeDelay("\"P1D\"");
        assertTrue(fromDay.contains("PT24H"));
        assertFalse(fromDay.contains("P1D"));
    }

    @Test
    void rejectsDelayOutsideTheWindow() {
        assertThrows(IllegalArgumentException.class, () -> delayOf("\"PT-1S\""));
        assertThrows(IllegalArgumentException.class, () -> delayOf("\"PT0.1S\""));
        assertThrows(IllegalArgumentException.class, () -> delayOf("\"PT0.5S\""));
        assertThrows(IllegalArgumentException.class, () -> delayOf("\"PT86400.5S\""));
    }

    @Test
    void acceptsNameAtTheCapAndRejectsEmptyOrLonger() {
        String capped = "n".repeat(ApplianceSchemas.NAME_MAX);
        ApplianceCommandList message = ApplianceJson.readCommandList(
                list(LIST_ID, appliance(ENTRY_ID, capped, "true", null)));
        assertEquals(capped, ((ApplianceCommandEntry) message.commands().getFirst()).target());
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, appliance(ENTRY_ID, "", "true", null))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, appliance(ENTRY_ID, "n".repeat(ApplianceSchemas.NAME_MAX + 1), "true", null))));
    }

    @Test
    void rejectsShapeFaultsOneAtATime() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                "{\"listId\":\"" + LIST_ID + "\",\"issuedAt\":\"" + ISSUED + "\",\"commands\":[],\"extra\":true}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, "")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, appliances(33))));
        ApplianceCommandList full = ApplianceJson.readCommandList(list(LIST_ID, appliances(32)));
        assertEquals(32, full.commands().size());
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, """
                %s,
                %s
                """.formatted(
                appliance(ENTRY_ID, "hallway", "true", null),
                appliance(ENTRY_TWO, "hallway", "true", null)))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, """
                %s,
                %s
                """.formatted(
                appliance(ENTRY_ID, "hallway", "true", null),
                appliance(ENTRY_TWO, "hallway", "true", "\"PT0S\"")))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, """
                %s,
                %s
                """.formatted(
                appliance(ENTRY_ID, "hallway", "false", "\"PT5M\""),
                appliance(ENTRY_TWO, "hallway", "false", "\"PT5M\"")))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "{\"type\":\"appliance\",\"id\":\"" + ENTRY_ID + "\",\"target\":\"hallway\",\"state\":true,\"note\":1}")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "{\"type\":\"appliance\",\"id\":\"" + ENTRY_ID + "\",\"target\":\"hallway\"}")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "{\"type\":\"appliance\",\"id\":\"" + ENTRY_ID + "\",\"target\":\"hallway\",\"state\":\"true\"}")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, """
                {"type":"cancel","id":"%s","cancelId":"%s","state":false}
                """.formatted(CANCEL_ENTRY, CANCEL_REF))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, """
                {"type":"cancel","id":"%s","cancelId":"%s","delay":"PT0S"}
                """.formatted(CANCEL_ENTRY, CANCEL_REF))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, """
                {"type":"cancel","id":"%s","target":"hallway","cancelId":"%s"}
                """.formatted(ENTRY_ID, CANCEL_KEY))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "{\"type\":\"cancel\",\"id\":\"" + ENTRY_ID + "\"}")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "{\"id\":\"" + ENTRY_ID + "\",\"target\":\"hallway\",\"state\":true}")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "{\"type\":\"other\",\"id\":\"" + ENTRY_ID + "\",\"target\":\"hallway\",\"state\":true}")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "null")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                "{\"listId\":\"" + LIST_ID + "\",\"issuedAt\":\" \",\"commands\":["
                        + appliance(ENTRY_ID, "hallway", "true", null) + "]}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                "{\"listId\":\"" + LIST_ID + "\",\"issuedAt\":\"yesterday\",\"commands\":["
                        + appliance(ENTRY_ID, "hallway", "true", null) + "]}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                "{\"listId\":\"" + LIST_ID + "\",\"commands\":["
                        + appliance(ENTRY_ID, "hallway", "true", null) + "]}"));
    }

    @Test
    void rejectsRepeatedIdsAndListIdCopiedOntoAnEntry() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(list(LIST_ID, """
                %s,
                %s
                """.formatted(
                appliance(ENTRY_ID, "hallway", "true", null),
                appliance(ENTRY_ID, "porch", "false", "\"PT5M\"")))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(ENTRY_ID, appliance(ENTRY_ID, "hallway", "true", null))));
    }

    @Test
    void idsAreCanonicalUuids() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(ODD_UUID, appliance(ENTRY_ID, "hallway", "true", null))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(UPPER_UUID, appliance(ENTRY_ID, "hallway", "true", null))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, appliance(ODD_UUID, "hallway", "true", null))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, appliance(UPPER_UUID, "hallway", "true", null))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, cancelId(ENTRY_ID, ODD_UUID))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, cancelId(ENTRY_ID, UPPER_UUID))));
        ApplianceCommandList accepted = ApplianceJson.readCommandList(
                list(LOWER_UUID, appliance("22222222-2222-4222-8222-222222222222", "hallway", "true", null)));
        assertEquals(LOWER_UUID, accepted.listId());
        assertEquals(
                LOWER_UUID,
                ((ApplianceCommandEntry) ApplianceJson.readCommandList(
                        list(LIST_ID, appliance(LOWER_UUID, "hallway", "true", null))).commands().getFirst()).id());
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readCommandList(
                list(LIST_ID, "{\"type\":\"appliance\",\"id\":\"cmd-1\",\"target\":\"hallway\",\"state\":true}")));
    }

    @Test
    void singleCommandStaysDeprecatedWithoutASince() throws Exception {
        Deprecated type = ApplianceCommandMessage.class.getAnnotation(Deprecated.class);
        assertNotNull(type);
        assertEquals("", type.since());
        Deprecated method = ApplianceSchemas.class.getMethod("commandsTopic", UUID.class).getAnnotation(Deprecated.class);
        assertNotNull(method);
        assertEquals("", method.since());
        String command = Files.readString(Path.of(
                "src/main/java/io/freedriver/mqtt/contract/ApplianceCommandMessage.java"));
        String schemas = Files.readString(Path.of(
                "src/main/java/io/freedriver/mqtt/contract/ApplianceSchemas.java"));
        assertTrue(command.contains("@deprecated"));
        assertTrue(command.contains("ApplianceCommandList"));
        assertTrue(command.contains("command-lists"));
        assertTrue(schemas.contains("@deprecated"));
        assertTrue(schemas.contains("commandListsTopic"));
        assertEquals(
                "freedriver/v1/" + LIST_ID + "/commands",
                ApplianceSchemas.commandsTopic(UUID.fromString(LIST_ID)));
    }

    private static Duration delayOf(String delayJson) {
        ApplianceCommandList message = ApplianceJson.readCommandList(
                list(LIST_ID, appliance(ENTRY_ID, "hallway", "true", delayJson)));
        return ((ApplianceCommandEntry) message.commands().getFirst()).delay();
    }

    private static String writeDelay(String delayJson) {
        return ApplianceJson.writeCommandList(ApplianceJson.readCommandList(
                list(LIST_ID, appliance(ENTRY_ID, "hallway", "true", delayJson))));
    }

    private static String list(String listId, String commands) {
        return "{\"listId\":\"" + listId + "\",\"issuedAt\":\"" + ISSUED + "\",\"commands\":[" + commands + "]}";
    }

    private static String appliance(String id, String target, String state, String delayJson) {
        String delay = delayJson == null ? "" : ",\"delay\":" + delayJson;
        return "{\"type\":\"appliance\",\"id\":\"" + id + "\",\"target\":\"" + target + "\",\"state\":" + state + delay + "}";
    }

    private static String cancelTarget(String id, String target) {
        return "{\"type\":\"cancel\",\"id\":\"" + id + "\",\"target\":\"" + target + "\"}";
    }

    private static String cancelId(String id, String cancelId) {
        return "{\"type\":\"cancel\",\"id\":\"" + id + "\",\"cancelId\":\"" + cancelId + "\"}";
    }

    private static String appliances(int count) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(appliance(uuid(10 + i), "t" + i, "true", null));
        }
        return body.toString();
    }

    private static String uuid(int n) {
        return "00000000-0000-4000-8000-%012d".formatted(n);
    }
}
