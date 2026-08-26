package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;

import java.util.Locale;
import java.util.UUID;

/**
 * Live MQTT command path. Compiled for later (#27) but not a CDI bean and not started.
 * Quarkus talks to Mosquitto on the docker network only — never mqtt.freedriver.io.
 * Browser never speaks MQTT. retain=false on commands, QoS 1.
 * {@code command} takes {@code instanceId} plus the alias {@code applianceName}.
 * {@code instanceName} is UX-only and is not on this message.
 */
public final class MqttLiveRoute {

    private MqttLiveRoute() {
    }

    public static void assertPrivateBroker(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("MQTT host required");
        }
        String normalized = host.toLowerCase(Locale.ROOT).strip();
        if (normalized.contains("mqtt.freedriver.io") || normalized.endsWith("freedriver.io")) {
            throw new IllegalStateException(
                    "Refusing public MQTT hostname. Use docker-network Mosquitto, never mqtt.freedriver.io");
        }
    }

    public static ApplianceCommandMessage command(UUID instanceId, String commandId, String applianceName, boolean on) {
        return new ApplianceCommandMessage(instanceId, commandId, applianceName, on);
    }
}
