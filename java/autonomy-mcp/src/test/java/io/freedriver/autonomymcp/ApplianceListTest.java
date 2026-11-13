package io.freedriver.autonomymcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.ApplianceStatus;
import io.freedriver.dashboard.model.InstanceView;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceListTest {

    private static final UUID HOME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SHED = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void houses_keep_names_ids_and_on_off_in_list_order() throws Exception {
        ApplianceMapResponse map = new ApplianceMapResponse(List.of(
                new InstanceView(HOME, "Home", "2026-11-12T00:00:00Z", false, false, List.of(
                        new ApplianceStatus("fridge", true),
                        new ApplianceStatus("lights", false))),
                new InstanceView(SHED, "Shed", null, true, false, List.of(
                        new ApplianceStatus("heater", false)))),
                "csrf");

        JsonNode houses = mapper.readTree(ApplianceList.json(mapper, map));

        assertEquals(2, houses.size());
        assertEquals("Home", houses.get(0).get("houseName").asText());
        assertEquals(HOME.toString(), houses.get(0).get("instanceId").asText());
        assertEquals("fridge", houses.get(0).get("appliances").get(0).get("name").asText());
        assertEquals("on", houses.get(0).get("appliances").get(0).get("state").asText());
        assertEquals("lights", houses.get(0).get("appliances").get(1).get("name").asText());
        assertEquals("off", houses.get(0).get("appliances").get(1).get("state").asText());
        assertEquals("Shed", houses.get(1).get("houseName").asText());
        assertEquals(SHED.toString(), houses.get(1).get("instanceId").asText());
        assertTrue(houses.get(0).get("lastUpdated") == null);
        assertTrue(houses.get(0).get("stale") == null);
        assertTrue(houses.get(0).get("csrfToken") == null);
    }

    @Test
    void empty_and_skipped_rows_are_an_array() throws Exception {
        assertEquals("[]", ApplianceList.json(mapper, null));
        assertEquals("[]", ApplianceList.json(mapper, new ApplianceMapResponse(List.of(), null)));
        assertEquals("[]", ApplianceList.json(mapper, new ApplianceMapResponse(null, null)));

        ApplianceMapResponse map = new ApplianceMapResponse(Arrays.asList(
                null,
                new InstanceView(null, "Missing", null, false, false, List.of(new ApplianceStatus("x", true))),
                new InstanceView(HOME, null, null, false, false, Arrays.asList(
                        null,
                        new ApplianceStatus("  ", true),
                        new ApplianceStatus("fan", false)))),
                "csrf");

        JsonNode houses = mapper.readTree(ApplianceList.json(mapper, map));
        assertEquals(1, houses.size());
        assertEquals("", houses.get(0).get("houseName").asText());
        assertEquals(HOME.toString(), houses.get(0).get("instanceId").asText());
        assertEquals(1, houses.get(0).get("appliances").size());
        assertEquals("fan", houses.get(0).get("appliances").get(0).get("name").asText());
        assertEquals("off", houses.get(0).get("appliances").get(0).get("state").asText());
    }
}
