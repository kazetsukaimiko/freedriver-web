package io.freedriver.app.metrics;

import io.freedriver.app.appliances.FixtureMqttConnection;
import io.freedriver.app.appliances.MockAutonomy;
import io.freedriver.app.appliances.MqttLiveClientTest;
import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A message the broker delivers through the fixture is timed under {@code freedriver.mqtt.handler} (#594). */
@QuarkusTest
@TestProfile(MqttLiveClientTest.LiveProfile.class)
class MqttHandlerTimerQuarkusTest {

    @Inject
    FixtureMqttConnection mqtt;

    @Inject
    MeterRegistry registry;

    @Test
    void anApplianceStateMessageIsOneRecordingOfMqttLiveClient() {
        long before = count("MqttLiveClient");
        mqtt.publishState(
                ApplianceSchemas.appliancesTopic(MockAutonomy.INSTANCE_ID),
                ApplianceJson.writeState(new ApplianceStateMessage(
                        "Cabin", null, List.of(new Appliance("hallway", true)))));
        assertEquals(before + 1, count("MqttLiveClient"));
    }

    @Test
    void aMetricsMessageIsOneRecordingOfMetricsMqttForwarder() {
        long before = count("MetricsMqttForwarder");
        mqtt.publishState(
                MetricsTopics.metricsTopic(MockAutonomy.INSTANCE_ID),
                "{\"key\":\"io.calls\",\"kind\":\"INCREMENT\",\"tags\":{\"from\":\"autonomy\"}}");
        assertEquals(before + 1, count("MetricsMqttForwarder"));
    }

    @Test
    void theMeterCarriesOnlyClassMethodAndException() {
        mqtt.publishState(
                MetricsTopics.metricsTopic(MockAutonomy.INSTANCE_ID),
                "{\"key\":\"io.calls\",\"kind\":\"INCREMENT\",\"tags\":{\"from\":\"autonomy\"}}");
        List<Meter> meters = List.copyOf(registry.find(MqttHandlerTimer.HANDLER).meters());
        assertFalse(meters.isEmpty());
        for (Meter meter : meters) {
            Set<String> keys = meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
            assertEquals(Set.of("class", "method", "exception"), keys, "tags of " + meter.getId());
            for (Tag tag : meter.getId().getTags()) {
                assertFalse(tag.getValue().contains(MockAutonomy.INSTANCE_ID.toString()), "instance id in " + tag);
                assertFalse(tag.getValue().contains("/"), "topic in " + tag);
            }
        }
        String scrape = given().when().get("/q/metrics").then().statusCode(200).extract().asString();
        assertTrue(scrape.lines().anyMatch(l -> l.startsWith("freedriver_mqtt_handler_seconds_count{")
                && l.contains("class=\"MetricsMqttForwarder\"") && l.contains("method=\"onBrokerMessage\"")),
                "no freedriver_mqtt_handler_seconds_count for MetricsMqttForwarder");
    }

    private long count(String owner) {
        Timer timer = registry.find(MqttHandlerTimer.HANDLER)
                .tag("class", owner).tag("method", "onBrokerMessage").tag("exception", "none").timer();
        return timer == null ? 0 : timer.count();
    }
}
