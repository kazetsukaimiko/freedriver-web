package io.freedriver.app.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Quarkus Micrometer interceptor times a bean whose class carries {@code @Timed}. */
@QuarkusTest
class IoTimerQuarkusTest {
    private static final String PROBE = TimedIoProbe.class.getName();

    @Inject
    TimedIoProbe probe;

    @Inject
    GrafanaMetricsEmitter emitter;

    @Inject
    MeterRegistry registry;

    @Test
    void callThroughTheBeanRecordsTheTimer() {
        long before = count("ping", "none");
        probe.ping();
        assertEquals(before + 1, count("ping", "none"));
    }

    @Test
    void throwingCallRecordsTheExceptionClassAndStillThrows() {
        long before = count("fail", "IllegalStateException");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, probe::fail);
        assertEquals(TimedIoProbe.MESSAGE, thrown.getMessage());
        assertEquals(before + 1, count("fail", "IllegalStateException"));
    }

    @Test
    void emitterCallsAreNotTimed() {
        probe.ping();
        emitter.increment("io.calls", Map.of("from", "probe"));
        assertFalse(blockingMeters().isEmpty());
        for (Meter meter : blockingMeters()) {
            String type = meter.getId().getTag("class");
            assertFalse(type != null && type.contains("GrafanaMetricsEmitter"), "timed: " + meter.getId());
        }
    }

    @Test
    void everyMeterCarriesOnlyClassMethodAndException() {
        probe.ping();
        assertThrows(IllegalStateException.class, probe::fail);
        Collection<Meter> meters = blockingMeters();
        assertTrue(meters.size() >= 2);
        for (Meter meter : meters) {
            Set<String> keys = meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
            assertEquals(Set.of("class", "method", "exception"), keys, "tags of " + meter.getId());
            for (Tag tag : meter.getId().getTags()) {
                String value = tag.getValue();
                assertFalse(value.contains(TimedIoProbe.MESSAGE), "message in " + tag);
                assertFalse(value.contains(TimedIoProbe.PHONE), "phone in " + tag);
                assertFalse(value.contains(TimedIoProbe.TOKEN), "token in " + tag);
            }
        }
    }

    @Test
    void scrapeExportsCountWithTheThreeTagsAndNoBuckets() {
        probe.ping();
        String scrape = given().when().get("/q/metrics").then().statusCode(200).extract().asString();
        String line = scrape.lines()
                .filter(l -> l.startsWith("freedriver_io_blocking_seconds_count{"))
                .filter(l -> l.contains("method=\"ping\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no freedriver_io_blocking_seconds_count for ping"));
        assertTrue(line.contains("class=\"" + PROBE + "\""), line);
        assertTrue(line.contains("exception=\"none\""), line);
        assertFalse(scrape.contains("freedriver_io_blocking_seconds_bucket"));
    }

    private long count(String method, String exception) {
        var timer = registry.find(IoTimer.BLOCKING)
                .tag("class", PROBE)
                .tag("method", method)
                .tag("exception", exception)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private Collection<Meter> blockingMeters() {
        return registry.find(IoTimer.BLOCKING).meters();
    }
}
