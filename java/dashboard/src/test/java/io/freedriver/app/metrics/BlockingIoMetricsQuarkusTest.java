package io.freedriver.app.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class BlockingIoMetricsQuarkusTest {

    @Inject
    BlockingIoProbe probe;

    @Inject
    GrafanaMetricsEmitter emitter;

    @Inject
    MeterRegistry registry;

    @Test
    void cdiInvocationLandsOnPrometheusMeter() {
        probe.ping();
        assertEquals(
                1.0,
                registry.get("freedriver_io_blocking")
                        .tag("method", "ping")
                        .tag("outcome", "ok")
                        .counter()
                        .count());
    }

    @Test
    void grafanaSinkCallsDoNotRecordBlockingIo() {
        emitter.increment("io.calls", Map.of("from", "probe"));
        assertNull(registry.find("freedriver_io_blocking").tag("type", "GrafanaMetricsEmitter").counter());
    }
}
