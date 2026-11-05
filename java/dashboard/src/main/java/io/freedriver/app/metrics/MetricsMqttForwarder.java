package io.freedriver.app.metrics;

import io.freedriver.app.appliances.MqttAcl;
import io.freedriver.app.appliances.MqttSettings;
import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.MqttLog;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscribes to each house metrics topic and forwards points to Grafana via Micrometer.
 * Uses the same MQTT session as appliance state. Does not publish.
 */
@BlockingIo
@ApplicationScoped
public class MetricsMqttForwarder {
    static final int MAX_BYTES = 64 * 1024;

    private final MqttSettings mqtt;
    private final MqttConnection connection;
    private final GrafanaMetricsEmitter emitter;

    @Inject
    public MetricsMqttForwarder(
            MqttSettings mqtt, MqttConnection connection, GrafanaMetricsEmitter emitter) {
        this.mqtt = mqtt;
        this.connection = connection;
        this.emitter = emitter;
    }

    void start(@Observes @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent event) {
        List<UUID> instances = mqtt.instances();
        if (instances.isEmpty()) {
            return;
        }
        try {
            for (UUID instanceId : instances) {
                connection.subscribe(
                        MetricsTopics.metricsTopic(instanceId), MetricsTopics.QOS, this::onBrokerMessage);
            }
        } catch (RuntimeException e) {
            MqttLog.metricsRejected("-", "subscribe-failed");
        }
    }

    void onBrokerMessage(String topic, byte[] payload) {
        UUID instanceId = MqttAcl.instanceIdFrom(topic, "metrics");
        if (instanceId == null || !mqtt.instances().contains(instanceId)) {
            MqttLog.metricsRejected(topic, "unknown-instance");
            return;
        }
        if (payload != null && payload.length > MAX_BYTES) {
            MqttLog.metricsRejected(topic, "size");
            return;
        }
        final MetricsPointPayload point;
        try {
            point = MetricsPointJson.read(payload);
        } catch (RuntimeException e) {
            MqttLog.metricsRejected(topic, "schema");
            return;
        }
        try {
            emitter.emit(withInstance(point, instanceId));
        } catch (RuntimeException e) {
            MqttLog.metricsRejected(topic, "kind");
            return;
        }
        MqttLog.metricsReceived(instanceId);
    }

    static MetricsPointPayload withInstance(MetricsPointPayload point, UUID instanceId) {
        Map<String, Object> tags = new HashMap<>(point.tags());
        tags.putIfAbsent("instanceId", instanceId.toString());
        return new MetricsPointPayload(point.key(), point.kind(), tags, point.timestamp());
    }
}
