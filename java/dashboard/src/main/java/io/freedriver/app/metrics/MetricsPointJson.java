package io.freedriver.app.metrics;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Strict JSON for metrics MQTT payloads. Does not log the body. */
public final class MetricsPointJson {
    static final ObjectMapper STRICT = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private MetricsPointJson() {}

    public static MetricsPointPayload read(byte[] payload) {
        try {
            String json = payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
            return STRICT.readValue(json, MetricsPointPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("schema");
        }
    }
}
