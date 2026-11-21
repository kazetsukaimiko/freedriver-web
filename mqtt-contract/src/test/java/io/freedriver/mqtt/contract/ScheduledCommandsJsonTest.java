package io.freedriver.mqtt.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ScheduledCommandsJsonTest {

    private static final String PUBLISHED = "2026-11-19T15:04:05Z";
    private static final String DUE = "2026-11-19T15:09:05Z";
    private static final String ENTRY_ID = "00000000-0000-4000-8000-000000000002";
    private static final String ENTRY_TWO = "00000000-0000-4000-8000-000000000003";
    private static final String ODD_UUID = "1-1-1-1-1";
    private static final String UPPER_UUID = "550E8400-E29B-41D4-A716-446655440000";
    private static final String LOWER_UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void topicIsQosOneAndNotRetained() {
        UUID instance = UUID.fromString("00000000-0000-4000-8000-000000000001");
        assertEquals("freedriver/v1/{instanceId}/scheduled", ApplianceSchemas.SCHEDULED_TOPIC_TEMPLATE);
        assertEquals("freedriver/v1/" + instance + "/scheduled", ApplianceSchemas.scheduledTopic(instance));
        assertEquals(1, ApplianceSchemas.QOS);
        assertFalse(ApplianceSchemas.RETAIN);
        assertThrows(NullPointerException.class, () -> ApplianceSchemas.scheduledTopic(null));
    }

    @Test
    void emptyPendingRoundTrips() {
        ScheduledCommandsMessage message = ApplianceJson.readScheduled("""
                {"publishedAt":"%s","pending":[]}
                """.formatted(PUBLISHED));
        assertEquals(Instant.parse(PUBLISHED), message.publishedAt());
        assertTrue(message.pending().isEmpty());
        assertEquals(message, ApplianceJson.readScheduled(ApplianceJson.writeScheduled(message)));
        assertTrue(ApplianceJson.writeScheduled(message).contains("\"pending\":[]"));
    }

    @Test
    void pendingEntryRoundTrips() {
        ScheduledCommandsMessage message = ApplianceJson.readScheduled(snapshot(pending(ENTRY_ID, "hallway", "true")));
        PendingCommand entry = message.pending().getFirst();
        assertEquals(ENTRY_ID, entry.id());
        assertEquals("hallway", entry.target());
        assertEquals(Boolean.TRUE, entry.state());
        assertEquals(Instant.parse(DUE), entry.dueAt());
        assertEquals(message, ApplianceJson.readScheduled(ApplianceJson.writeScheduled(message)));
    }

    @Test
    void accepts128AndRejects129() {
        ScheduledCommandsMessage accepted = ApplianceJson.readScheduled(snapshot(entries(128)));
        assertEquals(128, accepted.pending().size());
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(snapshot(entries(129))));
    }

    @Test
    void rejectsPendingFaultsOneAtATime() {
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                "{\"publishedAt\":\"" + PUBLISHED + "\",\"pending\":[],\"extra\":true}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                snapshot(pending(ENTRY_ID, "hallway", "true").replace("}", ",\"note\":1}"))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                snapshot("{\"id\":\"" + ENTRY_ID + "\",\"target\":\"hallway\",\"dueAt\":\"" + DUE + "\"}")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                snapshot(pending(ENTRY_ID, "hallway", "\"true\""))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(snapshot("null")));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                "{\"publishedAt\":\" \",\"pending\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                "{\"publishedAt\":\"yesterday\",\"pending\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled("{\"pending\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                "{\"publishedAt\":\"" + PUBLISHED + "\",\"pending\":["
                        + pending(ENTRY_ID, "", "true") + "]}"));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                snapshot(pending(ENTRY_ID, "n".repeat(ApplianceSchemas.NAME_MAX + 1), "true"))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(
                snapshot(pending(ENTRY_ID, "hallway", "true") + "," + pending(ENTRY_ID, "porch", "false"))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(snapshot(pending(ODD_UUID, "hallway", "true"))));
        assertThrows(IllegalArgumentException.class, () -> ApplianceJson.readScheduled(snapshot(pending(UPPER_UUID, "hallway", "true"))));
        ScheduledCommandsMessage accepted = ApplianceJson.readScheduled(snapshot(pending(LOWER_UUID, "hallway", "false")));
        assertEquals(LOWER_UUID, accepted.pending().getFirst().id());
        String capped = "n".repeat(ApplianceSchemas.NAME_MAX);
        assertEquals(capped, ApplianceJson.readScheduled(snapshot(pending(ENTRY_ID, capped, "true")))
                .pending().getFirst().target());
    }

    private static String snapshot(String pending) {
        return "{\"publishedAt\":\"" + PUBLISHED + "\",\"pending\":[" + pending + "]}";
    }

    private static String pending(String id, String target, String state) {
        return "{\"id\":\"" + id + "\",\"target\":\"" + target + "\",\"state\":" + state + ",\"dueAt\":\"" + DUE + "\"}";
    }

    private static String entries(int count) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(pending(uuid(10 + i), "hallway", i % 2 == 0 ? "true" : "false"));
        }
        return body.toString();
    }

    private static String uuid(int n) {
        return "00000000-0000-4000-8000-%012d".formatted(n);
    }
}
