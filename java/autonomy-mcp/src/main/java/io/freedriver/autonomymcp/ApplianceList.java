package io.freedriver.autonomymcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.ApplianceStatus;
import io.freedriver.dashboard.model.InstanceView;

final class ApplianceList {

    private ApplianceList() {
    }

    static String json(ObjectMapper mapper, ApplianceMapResponse map) throws JsonProcessingException {
        ArrayNode houses = mapper.createArrayNode();
        if (map != null && map.instances() != null) {
            for (InstanceView instance : map.instances()) {
                if (instance == null || instance.instanceId() == null) {
                    continue;
                }
                ObjectNode house = mapper.createObjectNode();
                house.put("houseName", instance.instanceName() == null ? "" : instance.instanceName());
                house.put("instanceId", instance.instanceId().toString());
                ArrayNode appliances = mapper.createArrayNode();
                if (instance.appliances() != null) {
                    for (ApplianceStatus appliance : instance.appliances()) {
                        if (appliance == null || appliance.applianceName() == null || appliance.applianceName().isBlank()) {
                            continue;
                        }
                        ObjectNode row = mapper.createObjectNode();
                        row.put("name", appliance.applianceName());
                        row.put("state", appliance.on() ? "on" : "off");
                        appliances.add(row);
                    }
                }
                house.set("appliances", appliances);
                houses.add(house);
            }
        }
        return mapper.writeValueAsString(houses);
    }
}
