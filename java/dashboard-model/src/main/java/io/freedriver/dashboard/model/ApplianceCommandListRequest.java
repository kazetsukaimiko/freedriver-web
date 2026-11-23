package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST body {@code { commands: [ { applianceName, on, delay? } ] }}.
 * The caller sends the commands only. The dashboard mints ids when it publishes the MQTT list.
 * Extra JSON fields are a 400, scoped to this body, while the global Jackson
 * FAIL_ON_UNKNOWN_PROPERTIES setting keeps its default.
 */
@DistinctApplianceDelay
public class ApplianceCommandListRequest {

    /** Same bound as {@code ApplianceCommandList.commands}. */
    public static final int MAX_COMMANDS = 32;

    @NotNull
    @Size(min = 1, max = MAX_COMMANDS)
    @Valid
    private List<@NotNull @Valid ApplianceCommandEntryRequest> commands;

    private final Map<String, Object> extras = new LinkedHashMap<>();

    public List<ApplianceCommandEntryRequest> getCommands() {
        return commands;
    }

    public void setCommands(List<ApplianceCommandEntryRequest> commands) {
        this.commands = commands;
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
