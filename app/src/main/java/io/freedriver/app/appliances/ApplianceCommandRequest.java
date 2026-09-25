package io.freedriver.app.appliances;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST body {@code { "on": bool }}. Extra JSON fields are rejected (400).
 * {@code @JsonAnySetter} collects extra fields and {@link #isKnownFieldsOnly()} turns them into a
 * Bean Validation failure, so the rule is scoped to this body and the global Jackson
 * FAIL_ON_UNKNOWN_PROPERTIES setting keeps its default.
 */
public class ApplianceCommandRequest {

    @NotNull
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

    @AssertTrue(message = "Unknown fields")
    @JsonIgnore
    public boolean isKnownFieldsOnly() {
        return extras.isEmpty();
    }
}
