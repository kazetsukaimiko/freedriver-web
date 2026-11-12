package io.freedriver.autonomymcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** JSON stored with a delayed switch. The worker has no bearer token of its own. */
final class DelayedSwitchPayload {

    private DelayedSwitchPayload() {
    }

    record Parsed(
            UUID instanceId,
            String applianceName,
            boolean on,
            String subject,
            String azp,
            Set<String> roles) {
    }

    static String json(
            ObjectMapper mapper,
            UUID instanceId,
            String applianceName,
            boolean on,
            String subject,
            String azp,
            Set<String> roles) {
        ObjectNode node = mapper.createObjectNode();
        node.put("instanceId", instanceId.toString());
        node.put("applianceName", applianceName);
        node.put("on", on);
        node.put("subject", subject);
        node.put("azp", azp == null ? "" : azp);
        ArrayNode storedRoles = node.putArray("roles");
        for (String role : roles) {
            storedRoles.add(role);
        }
        return node.toString();
    }

    static Parsed parse(ObjectMapper mapper, String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (node == null || !node.isObject() || !node.path("on").isBoolean()) {
                return null;
            }
            UUID instanceId = UUID.fromString(node.path("instanceId").asText(""));
            String applianceName = node.path("applianceName").asText("");
            if (applianceName.isBlank()) {
                return null;
            }
            String subject = node.path("subject").asText("");
            if (subject.isBlank()) {
                return null;
            }
            String azp = node.path("azp").asText("");
            Set<String> roles = new LinkedHashSet<>();
            for (JsonNode role : node.path("roles")) {
                if (role.isTextual() && !role.asText().isBlank()) {
                    roles.add(role.asText());
                }
            }
            return new Parsed(
                    instanceId,
                    applianceName,
                    node.get("on").asBoolean(),
                    subject,
                    azp.isBlank() ? null : azp,
                    Set.copyOf(roles));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return null;
        }
    }
}
