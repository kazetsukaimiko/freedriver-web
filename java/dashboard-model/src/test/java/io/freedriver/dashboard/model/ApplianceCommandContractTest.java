package io.freedriver.dashboard.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.freedriver.mqtt.contract.ApplianceCommandList;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON rules match the dashboard mapper: JavaTimeModule, unknown properties allowed on the mapper,
 * dates written as ISO-8601, durations written as timestamps unless a field asks for a string.
 * Strict boolean and duration fields reject coercion before bean validation.
 */
class ApplianceCommandContractTest {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

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
        assertTrue(body.getCommands().get(0).immediate());
        assertNull(body.getCommands().get(0).getDelay());
        assertTrue(body.getCommands().get(1).immediate());
        assertEquals(Duration.ZERO, body.getCommands().get(1).getDelay());
        assertFalse(body.getCommands().get(2).immediate());
        assertEquals(Duration.ofMinutes(5), body.getCommands().get(2).getDelay());
        ApplianceCommandListRequest again = read(JSON.writeValueAsString(body));
        assertEquals(Duration.ofMinutes(5), again.getCommands().get(2).getDelay());
        assertTrue(JSON.writeValueAsString(body).contains("\"delay\":\"PT5M\""));
    }

    @Test
    void stringBooleanAndNumericDelayFailWhileReading() {
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":"true"}]}"""));
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":true,"delay":5}]}"""));
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":1}]}"""));
        assertThrows(JsonProcessingException.class, () -> read("""
                {"commands":[{"applianceName":"hallway","on":true,"delay":null}]}"""));
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
                {"commands":[{"applianceName":"hallway","on":true,"extra":1}]}""").isEmpty());
        assertFalse(validate("""
                {"commands":[{"applianceName":"hallway","on":true}],"listId":"x"}""").isEmpty());
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
}
