package io.freedriver.app.metrics;

import io.freedriver.app.appliances.SubscribedHouses;
import io.freedriver.app.appliances.MqttAcl;
import io.freedriver.mqtt.MqttConnection;
import io.freedriver.app.mqttlog.HouseMqttLog;
import io.micrometer.core.annotation.Timed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Subscribes to each followed house's metrics topic and forwards points to Grafana via
 * Micrometer. {@link SubscribedHouses} holds the houses. Uses the same MQTT session as
 * appliance state, after {@code MqttLiveClient} has connected it. Does not publish.
 */
@Timed(IoTimer.BLOCKING)
@ApplicationScoped
public class MetricsMqttForwarder {
    static final int MAX_BYTES = 64 * 1024;

    private final SubscribedHouses subscriptions;
    private final MqttConnection connection;
    private final GrafanaMetricsEmitter emitter;
    private final MqttHandlerTimer handlers;

    @Inject
    public MetricsMqttForwarder(
            SubscribedHouses subscriptions, MqttConnection connection, GrafanaMetricsEmitter emitter,
            MqttHandlerTimer handlers) {
        this.subscriptions = subscriptions;
        this.connection = connection;
        this.emitter = emitter;
        this.handlers = handlers;
    }

    /** Subscribes to one house's metrics. A failure is logged; the house's appliance state still flows. */
    public void follow(UUID instanceId) {
        try {
            connection.subscribe(MetricsTopics.metricsTopic(instanceId), MetricsTopics.QOS,
                    handlers.timed(MetricsMqttForwarder.class, this::onBrokerMessage));
        } catch (RuntimeException e) {
            HouseMqttLog.metricsRejected(MetricsTopics.metricsTopic(instanceId), "subscribe-failed");
        }
    }

    public void unfollow(UUID instanceId) {
        connection.unsubscribe(MetricsTopics.metricsTopic(instanceId));
    }

    void onBrokerMessage(String topic, byte[] payload) {
        UUID instanceId = MqttAcl.instanceIdFrom(topic, "metrics");
        if (instanceId == null || !subscriptions.contains(instanceId)) {
            HouseMqttLog.metricsRejected(topic, "unknown-instance");
            return;
        }
        if (payload != null && payload.length > MAX_BYTES) {
            HouseMqttLog.metricsRejected(topic, "size");
            return;
        }
        final MetricsPointPayload point;
        try {
            point = MetricsPointJson.read(payload);
        } catch (RuntimeException e) {
            HouseMqttLog.metricsRejected(topic, "schema");
            return;
        }
        try {
            emitter.emit(withInstance(point, instanceId));
        } catch (RuntimeException e) {
            HouseMqttLog.metricsRejected(topic, "kind");
            return;
        }
        HouseMqttLog.metricsReceived(instanceId);
    }

    static MetricsPointPayload withInstance(MetricsPointPayload point, UUID instanceId) {
        Map<String, Object> tags = new HashMap<>(point.tags());
        tags.putIfAbsent("instanceId", instanceId.toString());
        return new MetricsPointPayload(point.key(), point.kind(), tags, point.timestamp());
    }
}
