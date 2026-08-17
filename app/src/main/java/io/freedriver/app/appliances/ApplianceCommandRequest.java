package io.freedriver.app.appliances;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.ws.rs.BadRequestException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST body {@code { "on": bool }}. Extra JSON fields are rejected (400).
 * Uses {@code @JsonAnySetter} so we do not flip FAIL_ON_UNKNOWN_PROPERTIES globally.
 */
public class ApplianceCommandRequest {

    private Boolean on;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public Boolean getOn() {
        return on;
    }

    public void setOn(Boolean on) {
        this.on = on;
    }

    @JsonAnySetter
    public void extra(String name, Object value) {
        extras.put(name, value);
    }

    public void assertValid() {
        if (!extras.isEmpty()) {
            throw new BadRequestException("Unknown fields: " + extras.keySet());
        }
        if (on == null) {
            throw new BadRequestException("Missing required field: on");
        }
    }
}
