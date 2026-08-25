package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;

import java.util.Locale;

/**
 * Live MQTT command path. Compiled for later (#27) but not a CDI bean and not started.
 * Quarkus talks to Mosquitto on the docker network only — never mqtt.freedriver.io.
 * Browser never speaks MQTT. retain=false on commands, QoS 1, schemaVersion 1.
 * {@code command} takes the alias {@code name}. No boards or UUIDs.
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

    public static ApplianceCommandMessage command(String commandId, String name, boolean on) {
        return new ApplianceCommandMessage(ApplianceSchemas.SCHEMA_VERSION, commandId, name, on);
    }
}
