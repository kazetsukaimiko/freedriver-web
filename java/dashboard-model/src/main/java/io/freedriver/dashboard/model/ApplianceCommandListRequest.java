package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * POST body {@code { commands: [ { applianceName, on, delay? } ] }}.
 * The caller sends the commands only. The dashboard mints ids when it publishes the MQTT list.
 * <p>
 * One entry per appliance name and delay. An absent delay counts as zero, compared with
 * {@link Duration#equals(Object)}. Strict token decoding is the dashboard reader's (#637),
 * configured like {@code ApplianceJson}; {@link ApplianceCommandEntryRequest} states that configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplianceCommandListRequest(
        @NotNull @Size(min = 1, max = MAX_COMMANDS)
        List<@NotNull @Valid ApplianceCommandEntryRequest> commands) {

    /** Same bound as {@code ApplianceCommandList.commands}. */
    public static final int MAX_COMMANDS = 32;

    public ApplianceCommandListRequest {
        if (commands != null) {
            commands = List.copyOf(commands);
        }
    }

    /**
     * One command per appliance name and delay. A missing delay matches an explicit zero.
     * A null entry or a null name is left to that entry's own constraints.
     */
    @AssertTrue(message = "one command per appliance and delay")
    @JsonIgnore
    public boolean isOneEntryPerApplianceAndDelay() {
        if (commands == null) {
            return true;
        }
        Set<NameAndDelay> seen = new HashSet<>();
        for (ApplianceCommandEntryRequest entry : commands) {
            if (entry != null && entry.applianceName() != null) {
                Duration delay = entry.delay() == null ? Duration.ZERO : entry.delay();
                if (!seen.add(new NameAndDelay(entry.applianceName(), delay))) {
                    return false;
                }
            }
        }
        return true;
    }

    private record NameAndDelay(String applianceName, Duration delay) {}
}
