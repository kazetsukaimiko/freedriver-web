package io.freedriver.dashboard.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import io.freedriver.mqtt.contract.ApplianceCommandList;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test mapper follows mqtt-contract {@code ApplianceJson.listMapper}: a copy of
 * {@link ApplianceSchemas#STRICT} with missing creator properties allowed, scalar coercion off,
 * and string-only {@link Duration} and {@link Instant} readers. Bean validation covers the
 * bounds and the one-entry-per-name-and-delay rule. The dashboard reader with this configuration
 * is #637.
 */
class ApplianceCommandContractTest {

    private static final ObjectMapper JSON = listMapper();

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String ID = "00000000-0000-4000-8000-000000000001";
    private static final String DUE = "2026-11-22T15:04:05Z";

    @Test
    void commandBoundMatchesTheMqttList() throws Exception {
        Size mqtt = ApplianceCommandList.class.getMethod("commands").getAnnotation(Size.class);
        assertEquals(1, mqtt.min());
        assertEquals(ApplianceCommandListRequest.MAX_COMMANDS, mqtt.max());
        assertEquals(32, ApplianceCommandListRequest.MAX_COMMANDS);
        assertEquals(64, ApplianceSchemas.NAME_MAX);
    }

    @Test
    void requestRoundTripKeepsAnImmediateAndADelayedEntry() throws Exception {
        ApplianceCommandListRequest body = read("""
                {"commands":[
                  {"applianceName":"hallway","on":true},
                  {"applianceName":"porch","on":false,"delay":"PT0S"},
                  {"applianceName":"lamp","on":true,"delay":"PT5M"}
                ]}""");
        assertTrue(VALIDATOR.validate(body).isEmpty());
        assertTrue(body.commands().get(0).immediate());
        assertNull(body.commands().get(0).delay());
        assertTrue(body.commands().get(1).immediate());
        assertEquals(Duration.ZERO, body.commands().get(1).delay());
        assertFalse(body.commands().get(2).immediate());
        assertEquals(Duration.ofMinutes(5), body.commands().get(2).delay());
        ApplianceCommandListRequest again = read(JSON.writeValueAsString(body));
        assertEquals(Duration.ofMinutes(5), again.commands().get(2).delay());
        assertTrue(JSON.writeValueAsString(body).contains("\"delay\":\"PT5M\""));
        ApplianceCommandListRequest nullDelay = read("""
                {"commands":[{"applianceName":"hallway","on":true,"delay":null}]}""");
        assertNull(nullDelay.commands().get(0).delay());
        assertTrue(nullDelay.commands().get(0).immediate());
        assertTrue(VALIDATOR.validate(nullDelay).isEmpty());
    }

    @Test
    void strictTokensFailWhileReading() {
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":"true"}]}"""));
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":true,"delay":5}]}"""));
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":1}]}"""));
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":true,"extra":1}]}"""));
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":true}],"listId":"x"}"""));
    }

    @Test
    void invalidBodiesFailBeanValidation() throws Exception {
        assertFalse(validate("""
                {"commands":[]}""").isEmpty());
        assertFalse(validate(listOf(33)).isEmpty());
        assertTrue(validate(listOf(32)).isEmpty());
        assertFalse(validate("""
                {"commands":[{"applianceName":"","on":true}]}""").isEmpty());
        assertFalse(validate("""
                {"commands":[{"applianceName":"%s","on":true}]}"""
                .formatted("n".repeat(ApplianceSchemas.NAME_MAX + 1))).isEmpty());
        assertTrue(validate("""
                {"commands":[{"applianceName":"%s","on":true}]}"""
                .formatted("n".repeat(ApplianceSchemas.NAME_MAX))).isEmpty());
        assertFalse(validate("""
                {"commands":[{"applianceName":"hallway","on":true,"delay":"PT0.5S"}]}""").isEmpty());
        assertFalse(validate("""
                {"commands":[{"applianceName":"hallway","on":true,"delay":"PT86401S"}]}""").isEmpty());
        assertTrue(validate("""
                {"commands":[{"applianceName":"hallway","on":true,"delay":"PT86400S"}]}""").isEmpty());
        assertFalse(validate("""
                {"commands":[
                  {"applianceName":"hallway","on":true},
                  {"applianceName":"hallway","on":false,"delay":"PT0S"}
                ]}""").isEmpty());
        assertFalse(validate("""
                {"commands":[
                  {"applianceName":"hallway","on":true,"delay":"PT1S"},
                  {"applianceName":"hallway","on":false,"delay":"PT1S"}
                ]}""").isEmpty());
        assertTrue(validate("""
                {"commands":[
                  {"applianceName":"hallway","on":true,"delay":"PT1S"},
                  {"applianceName":"hallway","on":false,"delay":"PT2S"},
                  {"applianceName":"porch","on":true,"delay":"PT1S"}
                ]}""").isEmpty());
    }

    @Test
    void eachOutcomeRoundTripsAndDueAtBelongsToScheduled() throws Exception {
        Instant due = Instant.parse(DUE);
        for (CommandListOutcome outcome : CommandListOutcome.values()) {
            Instant stamp = outcome == CommandListOutcome.SCHEDULED ? due : null;
            ApplianceCommandResult result = new ApplianceCommandResult(ID, "hallway", outcome, stamp);
            assertTrue(VALIDATOR.validate(result).isEmpty(), outcome.name());
            String json = JSON.writeValueAsString(result);
            assertTrue(json.contains("\"" + outcome.name().toLowerCase() + "\""), json);
            ApplianceCommandResult again = JSON.readValue(json, ApplianceCommandResult.class);
            assertEquals(result, again);
        }
        String scheduled = JSON.writeValueAsString(
                new ApplianceCommandResult(ID, "hallway", CommandListOutcome.SCHEDULED, due));
        assertTrue(scheduled.contains("\"dueAt\":\"" + DUE + "\""), scheduled);
        String confirmed = JSON.writeValueAsString(
                new ApplianceCommandResult(ID, "hallway", CommandListOutcome.CONFIRMED, null));
        assertFalse(confirmed.contains("dueAt"), confirmed);
        assertFalse(VALIDATOR.validate(
                new ApplianceCommandResult(ID, "hallway", CommandListOutcome.CONFIRMED, due)).isEmpty());
        assertFalse(VALIDATOR.validate(
                new ApplianceCommandResult(ID, "hallway", CommandListOutcome.SCHEDULED, null)).isEmpty());
        assertFalse(VALIDATOR.validate(
                new ApplianceCommandResult("550E8400-E29B-41D4-A716-446655440000", "hallway",
                        CommandListOutcome.TIMEOUT, null)).isEmpty());
        assertThrows(JsonProcessingException.class, () -> JSON.readValue("""
                {"id":"%s","applianceName":"hallway","outcome":"later"}""".formatted(ID),
                ApplianceCommandResult.class));
    }

    @Test
    void pendingEntryRoundTripsAndRejectsAStringBoolean() throws Exception {
        PendingApplianceCommand pending = new PendingApplianceCommand(ID, "hallway", false, Instant.parse(DUE));
        assertTrue(VALIDATOR.validate(pending).isEmpty());
        String json = JSON.writeValueAsString(pending);
        assertTrue(json.contains("\"applianceName\":\"hallway\""), json);
        assertTrue(json.contains("\"on\":false"), json);
        assertTrue(json.contains("\"dueAt\":\"" + DUE + "\""), json);
        assertEquals(pending, JSON.readValue(json, PendingApplianceCommand.class));
        List<PendingApplianceCommand> empty = JSON.readValue("[]",
                JSON.getTypeFactory().constructCollectionType(List.class, PendingApplianceCommand.class));
        assertTrue(empty.isEmpty());
        assertThrows(JsonProcessingException.class, () -> JSON.readValue("""
                {"id":"%s","applianceName":"hallway","on":"false","dueAt":"%s"}""".formatted(ID, DUE),
                PendingApplianceCommand.class));
        assertThrows(JsonProcessingException.class, () -> JSON.readValue("""
                {"id":"%s","applianceName":"hallway","on":false,"dueAt":1700000000}""".formatted(ID),
                PendingApplianceCommand.class));
    }

    private static ApplianceCommandListRequest read(String json) throws JsonProcessingException {
        return JSON.readValue(json, ApplianceCommandListRequest.class);
    }

    private static Set<ConstraintViolation<ApplianceCommandListRequest>> validate(String json) throws Exception {
        return VALIDATOR.validate(read(json));
    }

    private static String listOf(int count) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add("{\"applianceName\":\"a" + i + "\",\"on\":true}");
        }
        return "{\"commands\":[" + String.join(",", entries) + "]}";
    }

    /** Same construction as mqtt-contract {@code ApplianceJson.listMapper}. */
    private static ObjectMapper listMapper() {
        ObjectMapper mapper = ApplianceSchemas.STRICT.copy();
        mapper.disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        SimpleModule time = new SimpleModule("dashboard-command-contract-time");
        time.addSerializer(Duration.class, new DurationText());
        time.addDeserializer(Duration.class, new DurationTextReader());
        time.addSerializer(Instant.class, new InstantText());
        time.addDeserializer(Instant.class, new InstantTextReader());
        mapper.registerModule(time);
        return mapper;
    }

    /** Writes a duration with {@link Duration#toString()}. */
    private static final class DurationText extends JsonSerializer<Duration> {

        @Override
        public void serialize(Duration value, JsonGenerator generator, SerializerProvider serializers)
                throws java.io.IOException {
            generator.writeString(value.toString());
        }
    }

    /** Reads a duration with {@link Duration#parse(CharSequence)}. */
    private static final class DurationTextReader extends JsonDeserializer<Duration> {

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
    private static final class InstantText extends JsonSerializer<Instant> {

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                throws java.io.IOException {
            generator.writeString(value.toString());
        }
    }

    /** Reads an instant with {@link Instant#parse(CharSequence)}. */
    private static final class InstantTextReader extends JsonDeserializer<Instant> {

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
