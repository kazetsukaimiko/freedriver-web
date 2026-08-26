package io.freedriver.mqtt.contract;

import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.NonNull;

/**
 * Shared MQTT appliance JSON. Isolation is {@code instanceId} on the topic, not
 * in the body and not the MQTT protocol client-id. {@code instanceName} is UX
 * only: never a topic segment and never in an ACL.
 *
 * Topic methods interpolate {@code UUID.toString()} (hex + hyphens; cannot contain
 * {@code /}, {@code +}, or {@code #}). Version nibbles are not checked.
 */
public final class ApplianceSchemas {

    public static final int NAME_MAX = 64;

    public static final String APPLIANCES_TOPIC_TEMPLATE = "freedriver/v1/{instanceId}/appliances";
    public static final String COMMANDS_TOPIC_TEMPLATE = "freedriver/v1/{instanceId}/commands";

    public static final int QOS = 1;
    public static final boolean RETAIN = false;

    public static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .build();

    private ApplianceSchemas() {}

    public static String appliancesTopic(@NonNull UUID instanceId) {
        return "freedriver/v1/" + instanceId + "/appliances";
    }

    public static String commandsTopic(@NonNull UUID instanceId) {
        return "freedriver/v1/" + instanceId + "/commands";
    }
}
