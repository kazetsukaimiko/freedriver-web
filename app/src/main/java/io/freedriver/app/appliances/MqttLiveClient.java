package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Live MQTT adapter on the same CDI bus as {@link MockAutonomy}.
 * Compiled always; connects only when {@code live-commands=true}. Default/prod stays off.
 */
@ApplicationScoped
public class MqttLiveClient {

    private final AppliancesConfig appliances;
    private final MqttSettings mqtt;
    private final Instance<MqttConnection> connections;
    private final Event<ApplianceStateRouted> states;
    private MqttConnection connection;

    @Inject
    public MqttLiveClient(
            AppliancesConfig appliances,
            MqttSettings mqtt,
            Instance<MqttConnection> connections,
            Event<ApplianceStateRouted> states) {
        this.appliances = appliances;
        this.mqtt = mqtt;
        this.connections = connections;
        this.states = states;
    }

    void start(@Observes StartupEvent event) {
        if (!appliances.liveCommands()) {
            return;
        }
        if (appliances.mock()) {
            throw new IllegalStateException("live MQTT must not run with mock=true");
        }
        List<UUID> instances = mqtt.instances();
        if (instances.isEmpty()) {
            throw new IllegalStateException("live-commands requires freedriver.mqtt.instance-ids");
        }
        connection = connections.get();
        connection.connect();
        for (UUID instanceId : instances) {
            String topic = ApplianceSchemas.appliancesTopic(instanceId);
            connection.subscribe(topic, ApplianceSchemas.QOS, this::onBrokerMessage);
        }
    }

    void onCommand(@Observes ApplianceCommandRouted routed) {
        if (connection == null) {
            return;
        }
        String topic = ApplianceSchemas.commandsTopic(routed.instanceId());
        try {
            connection.publish(
                    topic,
                    ApplianceJson.writeCommand(routed.command()).getBytes(StandardCharsets.UTF_8),
                    ApplianceSchemas.QOS,
                    ApplianceSchemas.RETAIN);
        } catch (RuntimeException e) {
            Log.warn("live MQTT command publish failed", e);
        }
    }

    void onBrokerMessage(String topic, byte[] payload) {
        UUID instanceId = MqttAcl.instanceIdFrom(topic, "appliances");
        if (instanceId == null) {
            return;
        }
        try {
            ApplianceStateMessage state = ApplianceJson.readState(new String(payload, StandardCharsets.UTF_8));
            states.fire(new ApplianceStateRouted(instanceId, state));
        } catch (RuntimeException e) {
            Log.warn("live MQTT state rejected", e);
        }
    }

    @PreDestroy
    void stop() {
        if (connection != null) {
            connection.close();
        }
    }
}
