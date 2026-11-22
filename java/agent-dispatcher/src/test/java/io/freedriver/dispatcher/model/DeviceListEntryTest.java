package io.freedriver.dispatcher.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The device-list record: fields, enum names, and JSON. The clock cases land with the implementation. */
class DeviceListEntryTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void device_view_keeps_its_five_components() {
        assertEquals(List.of("deviceId", "hostname", "agents", "lastHeartbeat", "activity"),
                List.of(DeviceView.class.getRecordComponents()).stream().map(RecordComponent::getName).toList());
    }

    @Test
    void enum_names_are_the_roster_state_strings() {
        assertEquals(List.of("IDLE", "BUSY", "UNKNOWN", "OFFLINE"),
                List.of(AgentState.values()).stream().map(AgentState::name).toList());
    }

    @Test
    void json_carries_mixed_states_and_utc_instants() throws Exception {
        Instant report = Instant.parse("2026-11-17T12:00:00Z");
        Instant seen = Instant.parse("2026-11-17T12:05:00Z");
        Map<String, AgentState> states = new LinkedHashMap<>();
        states.put("yuni", AgentState.BUSY);
        states.put("kai", AgentState.IDLE);
        DeviceListEntry entry = new DeviceListEntry("pc-a", "a.local", List.of("yuni", "kai"), report, report, seen,
                states);

        JsonNode json = JSON.readTree(JSON.writeValueAsString(entry));

        assertEquals("pc-a", json.path("deviceId").asText());
        assertEquals("a.local", json.path("hostname").asText());
        List<String> agents = new ArrayList<>();
        json.path("agents").forEach(item -> agents.add(item.asText()));
        assertEquals(List.of("yuni", "kai"), agents);
        assertEquals("2026-11-17T12:00:00Z", json.path("lastHeartbeat").asText());
        assertEquals("2026-11-17T12:00:00Z", json.path("reportedAt").asText());
        assertEquals("2026-11-17T12:05:00Z", json.path("seenAt").asText());
        assertEquals("BUSY", json.path("stateByAgent").path("yuni").asText());
        assertEquals("IDLE", json.path("stateByAgent").path("kai").asText());
        assertFalse(json.has("activity"));
    }

    @Test
    void a_host_with_neither_time_and_no_agents_is_null_times_and_an_empty_map() throws Exception {
        DeviceListEntry entry = new DeviceListEntry("pc-none", "none.local", List.of(), null, null, null, Map.of());

        JsonNode json = JSON.readTree(JSON.writeValueAsString(entry));

        assertTrue(json.path("lastHeartbeat").isNull());
        assertTrue(json.path("reportedAt").isNull());
        assertTrue(json.path("seenAt").isNull());
        assertTrue(json.path("stateByAgent").isObject());
        assertEquals(0, json.path("stateByAgent").size());
        assertFalse(json.has("activity"));
    }
}
