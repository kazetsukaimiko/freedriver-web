package io.freedriver.app.appliances;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Appliance JSON contract. FAIL_ON_UNKNOWN_PROPERTIES applies to these DTOs only,
 * not the global Quarkus ObjectMapper.
 */
public final class ApplianceSchemas {

    public static final int SCHEMA_VERSION = 1;
    public static final Pattern APPLIANCE_ID = Pattern.compile("[a-z0-9-]{1,64}");
    public static final int NAME_MAX = 64;
    public static final String STATE_TOPIC = "freedriver/v1/home/appliances";
    public static final String COMMAND_TOPIC = "freedriver/v1/home/commands";
    public static final int QOS = 1;

    public static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ApplianceSchemas() {
    }

    public static boolean validId(String id) {
        return id != null && APPLIANCE_ID.matcher(id).matches();
    }

    public static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= NAME_MAX;
    }

    public static Duration boundedTimeout(Duration requested, Duration max) {
        Duration fallback = Duration.ofSeconds(5);
        Duration use = requested;
        if (use == null || use.isZero() || use.isNegative()) {
            use = fallback;
        }
        Duration cap = max == null || max.isZero() || max.isNegative() ? Duration.ofSeconds(30) : max;
        if (use.compareTo(cap) > 0) {
            return cap;
        }
        Duration floor = Duration.ofMillis(50);
        if (use.compareTo(floor) < 0) {
            return floor;
        }
        return use;
    }
}
