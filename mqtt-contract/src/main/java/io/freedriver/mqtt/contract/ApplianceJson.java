package io.freedriver.mqtt.contract;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import lombok.NonNull;

/**
 * Parses and writes appliance state, the single command, a command list, and a scheduled snapshot.
 */
public final class ApplianceJson {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * {@link ApplianceSchemas#STRICT} with missing creator properties allowed and scalar coercion off.
     * Duration and Instant are strings parsed with {@link Duration#parse} and {@link Instant#parse}.
     */
    static final ObjectMapper LISTS = listMapper();

    private ApplianceJson() {}

    public static ApplianceStateMessage readState(@NonNull String json) {
        return read(ApplianceSchemas.STRICT, json, ApplianceStateMessage.class, "Rejected appliance state");
    }

    public static ApplianceCommandMessage readCommand(@NonNull String json) {
        return read(ApplianceSchemas.STRICT, json, ApplianceCommandMessage.class, "Rejected appliance command");
    }

    public static ApplianceCommandList readCommandList(@NonNull String json) {
        return read(LISTS, json, ApplianceCommandList.class, "Rejected appliance command list");
    }

    public static ScheduledCommandsMessage readScheduled(@NonNull String json) {
        return read(LISTS, json, ScheduledCommandsMessage.class, "Rejected scheduled commands");
    }

    public static String writeState(@NonNull ApplianceStateMessage message) {
        return write(ApplianceSchemas.STRICT, message, "Failed to serialize appliance state");
    }

    public static String writeCommand(@NonNull ApplianceCommandMessage message) {
        return write(ApplianceSchemas.STRICT, message, "Failed to serialize appliance command");
    }

    public static String writeCommandList(@NonNull ApplianceCommandList message) {
        return write(LISTS, message, "Failed to serialize appliance command list");
    }

    public static String writeScheduled(@NonNull ScheduledCommandsMessage message) {
        return write(LISTS, message, "Failed to serialize scheduled commands");
    }

    private static ObjectMapper listMapper() {
        ObjectMapper mapper = ApplianceSchemas.STRICT.copy();
        mapper.disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        SimpleModule time = new SimpleModule("mqtt-contract-time");
        time.addSerializer(Duration.class, new DurationText());
        time.addDeserializer(Duration.class, new DurationTextReader());
        time.addSerializer(Instant.class, new InstantText());
        time.addDeserializer(Instant.class, new InstantTextReader());
        mapper.registerModule(time);
        return mapper;
    }

    private static <T> T read(ObjectMapper mapper, String json, Class<T> type, String prefix) {
        try {
            T parsed = mapper.readValue(json, type);
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

    private static String write(ObjectMapper mapper, Object message, String prefix) {
        try {
            return mapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException(prefix, e);
        }
    }

    /** Writes a duration with {@link Duration#toString()}. */
    static final class DurationText extends JsonSerializer<Duration> {

        @Override
        public void serialize(Duration value, JsonGenerator generator, SerializerProvider serializers)
                throws java.io.IOException {
            generator.writeString(value.toString());
        }
    }

    /** Reads a duration with {@link Duration#parse(CharSequence)}. */
    static final class DurationTextReader extends JsonDeserializer<Duration> {

        @Override
        public Duration deserialize(JsonParser parser, DeserializationContext context)
                throws java.io.IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw context.wrongTokenException(
                        parser, Duration.class, JsonToken.VALUE_STRING, "ISO-8601 duration");
            }
            String text = parser.getText();
            try {
                return Duration.parse(text);
            } catch (DateTimeParseException ex) {
                throw badText(context, text, Duration.class, "ISO-8601 duration");
            }
        }
    }

    /** Writes an instant with {@link Instant#toString()}. */
    static final class InstantText extends JsonSerializer<Instant> {

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                throws java.io.IOException {
            generator.writeString(value.toString());
        }
    }

    /** Reads an instant with {@link Instant#parse(CharSequence)}. */
    static final class InstantTextReader extends JsonDeserializer<Instant> {

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context)
                throws java.io.IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw context.wrongTokenException(
                        parser, Instant.class, JsonToken.VALUE_STRING, "ISO-8601 instant");
            }
            String text = parser.getText();
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ex) {
                throw badText(context, text, Instant.class, "ISO-8601 instant");
            }
        }
    }

    private static JsonMappingException badText(
            DeserializationContext context, String text, Class<?> type, String why) {
        return context.weirdStringException(text, type, why);
    }
}
