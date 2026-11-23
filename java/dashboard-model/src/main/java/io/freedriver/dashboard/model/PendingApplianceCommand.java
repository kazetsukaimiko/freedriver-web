package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.CanonicalUuid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DateTimeException;
import java.time.Instant;

/**
 * One pending delayed command returned by the scheduled list.
 * JSON names are {@code applianceName} and {@code on}. {@code on} is a JSON boolean.
 * {@code dueAt} is an ISO-8601 string.
 */
public record PendingApplianceCommand(
        @NotBlank @CanonicalUuid String id,
        @NotBlank @Size(min = 1, max = ApplianceSchemas.NAME_MAX) String applianceName,
        @NotNull @JsonFormat(shape = JsonFormat.Shape.BOOLEAN, lenient = OptBoolean.FALSE) Boolean on,
        @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) Instant dueAt) {

    /**
     * Jackson 2.19 coerces a string into {@link Boolean} and a number into {@link Instant}
     * before {@link JsonFormat} can refuse them. This factory sees the tokens.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PendingApplianceCommand read(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("pending command is a JSON object");
        }
        JsonNode on = node.get("on");
        if (on == null || !on.isBoolean()) {
            throw new IllegalArgumentException("on is a JSON boolean");
        }
        JsonNode dueAt = node.get("dueAt");
        if (dueAt == null || !dueAt.isTextual()) {
            throw new IllegalArgumentException("dueAt is an ISO-8601 string");
        }
        try {
            return new PendingApplianceCommand(
                    text(node, "id"),
                    text(node, "applianceName"),
                    on.booleanValue(),
                    Instant.parse(dueAt.textValue()));
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("dueAt is an ISO-8601 string");
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " is a JSON string");
        }
        return value.textValue();
    }
}
