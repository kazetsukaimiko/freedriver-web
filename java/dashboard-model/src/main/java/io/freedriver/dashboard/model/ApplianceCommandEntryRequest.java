package io.freedriver.dashboard.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.DelayWindow;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DateTimeException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry of a command-list POST: {@code { applianceName, on, delay? }}.
 * {@code on} is a JSON boolean and {@code delay} is a JSON string.
 * {@code @JsonFormat(lenient = FALSE)} is on the accessors. Jackson 2.19 still coerces a string
 * boolean and a numeric duration, so the setters read the JSON node and reject the wrong token
 * before the value is stored. An absent delay and an explicit zero are both immediate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApplianceCommandEntryRequest {

    @NotBlank
    @Size(min = 1, max = ApplianceSchemas.NAME_MAX)
    private String applianceName;

    @NotNull
    private Boolean on;

    @DelayWindow
    private Duration delay;

    private final Map<String, Object> extras = new LinkedHashMap<>();

    public String getApplianceName() {
        return applianceName;
    }

    public void setApplianceName(String applianceName) {
        this.applianceName = applianceName;
    }

    @JsonFormat(shape = JsonFormat.Shape.BOOLEAN, lenient = OptBoolean.FALSE)
    public Boolean getOn() {
        return on;
    }

    public void setOn(JsonNode on) {
        if (on == null || !on.isBoolean()) {
            throw new IllegalArgumentException("on is a JSON boolean");
        }
        this.on = on.booleanValue();
    }

    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    public Duration getDelay() {
        return delay;
    }

    public void setDelay(JsonNode delay) {
        if (delay == null || delay.isNull() || !delay.isTextual()) {
            throw new IllegalArgumentException("delay is a JSON string");
        }
        try {
            this.delay = Duration.parse(delay.textValue());
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("delay is an ISO-8601 duration");
        }
    }

    /** Absent and zero delays are the immediate command. A positive delay is deferred. */
    @JsonIgnore
    public boolean immediate() {
        return delay == null || delay.isZero();
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
