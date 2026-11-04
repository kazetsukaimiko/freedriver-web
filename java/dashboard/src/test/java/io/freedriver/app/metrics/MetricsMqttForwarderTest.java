package io.freedriver.app.metrics;

import io.freedriver.app.appliances.FixtureMqttConnection;
import io.freedriver.app.appliances.MockAutonomy;
import io.freedriver.app.appliances.MqttLiveClientTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(MqttLiveClientTest.LiveProfile.class)
class MetricsMqttForwarderTest {

    @Inject
    FixtureMqttConnection mqtt;

    @Inject
    MeterRegistry registry;

    @Inject
    MetricsMqttForwarder forwarder;

    @Test
    void mqttPointLandsOnPrometheusMeter() {
        String topic = MetricsTopics.metricsTopic(MockAutonomy.INSTANCE_ID);
        assertTrue(mqtt.subscriptions().contains(topic));
        mqtt.publishState(
                topic,
                "{\"key\":\"io.calls\",\"kind\":\"INCREMENT\",\"tags\":{\"from\":\"autonomy\"}}");
        assertEquals(
                1.0,
                registry.get("freedriver_io_calls").tag("instanceid", MockAutonomy.INSTANCE_ID.toString()).counter().count());
    }

    @Test
    void unknownInstanceIsDropped() {
        UUID unknown = UUID.fromString("22222222-2222-4222-8222-222222222222");
        String topic = MetricsTopics.metricsTopic(unknown);
        forwarder.onBrokerMessage(
                topic, "{\"key\":\"io.calls\",\"kind\":\"INCREMENT\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(registry.find("freedriver_io_calls").tag("instanceid", unknown.toString()).counter() == null
                || registry.find("freedriver_io_calls").tag("instanceid", unknown.toString()).counter().count() == 0);
    }
}
