package io.freedriver.mqtt.contract;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import lombok.NonNull;

/**
 * MQTT handler codec. Records have no parse/toJson. Call this from the connector
 * that already knows {@code instanceId} from the topic.
 */
public final class ApplianceJson {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private ApplianceJson() {}

    public static ApplianceStateMessage readState(@NonNull String json) {
        return read(json, ApplianceStateMessage.class, "Rejected appliance state");
    }

    public static ApplianceCommandMessage readCommand(@NonNull String json) {
        return read(json, ApplianceCommandMessage.class, "Rejected appliance command");
    }

    public static String writeState(@NonNull ApplianceStateMessage message) {
        return write(message, "Failed to serialize appliance state");
    }

    public static String writeCommand(@NonNull ApplianceCommandMessage message) {
        return write(message, "Failed to serialize appliance command");
    }

    private static <T> T read(String json, Class<T> type, String prefix) {
        try {
            T parsed = ApplianceSchemas.STRICT.readValue(json, type);
            Set<ConstraintViolation<T>> violations = VALIDATOR.validate(parsed);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .collect(Collectors.joining("; "));
                throw new IllegalArgumentException("Rejected MQTT contract: " + detail);
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(prefix + ": " + e.getMessage(), e);
        }
    }

    private static String write(Object message, String prefix) {
        try {
            return ApplianceSchemas.STRICT.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException(prefix, e);
        }
    }
}
