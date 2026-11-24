package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.DelayWindow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;

/**
 * One entry of a command-list POST: {@code { applianceName, on, delay? }}.
 * {@code on} is a JSON boolean. {@code delay} is a JSON string, or absent.
 * An absent delay, a JSON null delay, and an explicit zero are all immediate.
 * A JSON null reaches the record as null because the string reader sees only a JSON string,
 * the same as {@code ApplianceJson}.
 * <p>
 * The dashboard reader (#637) rejects a string boolean, a numeric delay, and an unknown field.
 * That reader is configured like {@code ApplianceJson}: scalar coercion off, unknown properties
 * fail, and a duration is read with {@link Duration#parse(CharSequence)} from a JSON string.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplianceCommandEntryRequest(
        @NotBlank @Size(min = 1, max = ApplianceSchemas.NAME_MAX) String applianceName,
        @NotNull Boolean on,
        @DelayWindow Duration delay) {

    /** Absent and zero delays are the immediate command. A positive delay is deferred. */
    @JsonIgnore
    public boolean immediate() {
        return delay == null || delay.isZero();
    }
}
