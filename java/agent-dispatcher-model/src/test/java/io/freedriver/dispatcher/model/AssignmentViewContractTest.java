package io.freedriver.dispatcher.model;

import static io.freedriver.dispatcher.model.ContractJson.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The JSON contract of {@link AssignmentView#finishBy()} (#789). */
class AssignmentViewContractTest {

    private static final UUID ASSIGNMENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant FINISH_BY = Instant.parse("2026-11-23T20:10:00Z");

    private static AssignmentView view(Instant finishBy) {
        return new AssignmentView(ASSIGNMENT, JOB, "device-1", "agent-1", Channel.TEXT, "hello",
                AssignmentState.RUNNING, null, null, OnBusy.queue, "delivery-1", finishBy);
    }

    @Test
    void finishBy_serializes_as_an_iso_8601_instant_when_set() {
        JsonNode json = JSON.valueToTree(view(FINISH_BY));

        assertTrue(json.get("finishBy").isTextual());
        assertEquals("2026-11-23T20:10:00Z", json.get("finishBy").asText());
    }

    @Test
    void finishBy_serializes_as_json_null_when_unset() {
        JsonNode json = JSON.valueToTree(view(null));

        assertTrue(json.has("finishBy"));
        assertTrue(json.get("finishBy").isNull());
    }

    @Test
    void json_without_finishBy_reads_as_null() throws Exception {
        String before = "{\"assignmentId\":\"" + ASSIGNMENT + "\",\"jobId\":\"" + JOB + "\",\"deviceId\":\"device-1\","
                + "\"agentName\":\"agent-1\",\"channel\":\"TEXT\",\"prompt\":\"hello\",\"state\":\"RUNNING\","
                + "\"agentSessionId\":null,\"note\":null,\"on_busy\":\"queue\",\"deliveryRef\":\"delivery-1\"}";

        AssignmentView read = JSON.readValue(before, AssignmentView.class);

        assertNull(read.finishBy());
        assertEquals(view(null), read);
    }

    @Test
    void agentSessionId_serializes_as_the_json_property_agentSessionId() throws Exception {
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000003");
        AssignmentView view = new AssignmentView(ASSIGNMENT, JOB, "device-1", "agent-1", Channel.TEXT, "hello",
                AssignmentState.RUNNING, session, null, OnBusy.queue, "delivery-1", null);

        JsonNode json = JSON.valueToTree(view);

        assertEquals(session.toString(), json.get("agentSessionId").asText());
        assertFalse(json.has("grokSessionId"));
        assertEquals(12, json.size());
        assertEquals(session, JSON.readValue(json.toString(), AssignmentView.class).agentSessionId());
    }

    @Test
    @SuppressWarnings("removal")
    void the_deprecated_grokSessionId_accessor_returns_agentSessionId() {
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000003");
        AssignmentView view = new AssignmentView(ASSIGNMENT, JOB, "device-1", "agent-1", Channel.TEXT, "hello",
                AssignmentState.RUNNING, session, null, OnBusy.queue, "delivery-1", null);

        assertEquals(view.agentSessionId(), view.grokSessionId());
    }

    @Test
    void a_view_with_finishBy_round_trips() throws Exception {
        AssignmentView view = view(FINISH_BY);

        assertEquals(view, JSON.readValue(JSON.writeValueAsString(view), AssignmentView.class));
    }

    @Test
    void the_eleven_argument_constructor_leaves_finishBy_null() {
        AssignmentView before = new AssignmentView(ASSIGNMENT, JOB, "device-1", "agent-1", Channel.TEXT, "hello",
                AssignmentState.RUNNING, null, null, OnBusy.queue, "delivery-1");

        assertEquals(view(null), before);
    }
}
