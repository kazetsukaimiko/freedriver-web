package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * POST body for a switch that runs later. Extra JSON fields are rejected.
 * The caller subject is the authenticated identity, not a field of this body.
 */
public class ScheduledSwitchRequest {

    @NotNull
    private UUID instanceId;

    @NotBlank
    @Size(max = 64)
    private String applianceName;

    @NotNull
    private Boolean on;

    @NotNull
    private Instant dueAt;

    private final Map<String, Object> extras = new LinkedHashMap<>();

    public UUID getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(UUID instanceId) {
        this.instanceId = instanceId;
    }

    public String getApplianceName() {
        return applianceName;
    }

    public void setApplianceName(String applianceName) {
        this.applianceName = applianceName;
    }

    public Boolean getOn() {
        return on;
    }

    public void setOn(Boolean on) {
        this.on = on;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
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
