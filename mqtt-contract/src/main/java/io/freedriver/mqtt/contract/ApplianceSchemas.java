package io.freedriver.mqtt.contract;

import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.NonNull;

/**
 * Topic paths, QoS, retain, and the strict JSON mapper for appliance messages.
 * {@code instanceId} is the topic segment for one autonomy instance.
 * Topic helpers format that id with {@code UUID.toString()} (hex digits and hyphens)
 * as one topic level. Publications use {@link #QOS} and {@link #RETAIN}.
 */
public final class ApplianceSchemas {

    public static final int NAME_MAX = 64;

    /** Longest delay a command-list entry may carry, in whole seconds. */
    public static final int MAX_DELAY_SECONDS = 86400;

    /** Most pending entries one scheduled snapshot may carry. */
    public static final int MAX_PENDING = 128;

    public static final String APPLIANCES_TOPIC_TEMPLATE = "freedriver/v1/{instanceId}/appliances";
    public static final String COMMANDS_TOPIC_TEMPLATE = "freedriver/v1/{instanceId}/commands";
    public static final String COMMAND_LISTS_TOPIC_TEMPLATE = "freedriver/v1/{instanceId}/command-lists";
    public static final String SCHEDULED_TOPIC_TEMPLATE = "freedriver/v1/{instanceId}/scheduled";

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

    /**
     * @deprecated Use {@link #commandListsTopic(UUID)} on the command-lists topic.
     */
    @Deprecated
    public static String commandsTopic(@NonNull UUID instanceId) {
        return "freedriver/v1/" + instanceId + "/commands";
    }

    public static String commandListsTopic(@NonNull UUID instanceId) {
        return "freedriver/v1/" + instanceId + "/command-lists";
    }

    public static String scheduledTopic(@NonNull UUID instanceId) {
        return "freedriver/v1/" + instanceId + "/scheduled";
    }
}
