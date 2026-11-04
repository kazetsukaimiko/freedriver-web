package io.freedriver.app.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrafanaMetricsEmitterTest {

    @Test
    void incrementCountAndGaugeWriteMeters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GrafanaMetricsEmitter emitter = new GrafanaMetricsEmitter(registry);

        emitter.increment("io.calls", Map.of("instanceId", "house-1"));
        emitter.count("io.bytes", Map.of("instanceId", "house-1", "value", 4));
        emitter.gauge("io.queue", Map.of("instanceId", "house-1", "value", 9));

        assertEquals(1.0, registry.get("freedriver_io_calls").counter().count());
        assertEquals(4.0, registry.get("freedriver_io_bytes").counter().count());
        assertEquals(9.0, registry.get("freedriver_io_queue").gauge().value());
    }

    @Test
    void jsonRoundTripKeepsKind() throws Exception {
        MetricsPointPayload original = new MetricsPointPayload(
                "io.calls", "COUNT", Map.of("instanceId", "house-1", "value", 3), Instant.parse("2026-11-02T01:00:00Z"));
        MetricsPointPayload read = MetricsPointJson.read(MetricsPointJson.STRICT.writeValueAsBytes(original));
        assertEquals(original.key(), read.key());
        assertEquals(MetricsPointPayload.Kind.COUNT, read.parsedKind());
        assertEquals("house-1", read.tags().get("instanceId"));
    }

    @Test
    void omittedTagsAndTimestampStillRead() {
        MetricsPointPayload read =
                MetricsPointJson.read(
                        "{\"key\":\"io.calls\",\"kind\":\"INCREMENT\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("io.calls", read.key());
        assertEquals(MetricsPointPayload.Kind.INCREMENT, read.parsedKind());
        assertEquals(Map.of(), read.tags());
    }

    @Test
    void blankKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsPointPayload(" ", "INCREMENT", Map.of(), Instant.now()));
    }
}
