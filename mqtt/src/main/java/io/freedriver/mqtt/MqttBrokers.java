package io.freedriver.mqtt;

import java.util.Locale;

/**
 * Checks a live broker target and a topic before a session uses them.
 * The target is a docker-network host, port 8883, and TLS.
 * The topic is an exact filter.
 */
public final class MqttBrokers {

    private MqttBrokers() {
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

    public static void assertLiveBroker(String host, int port, boolean tls) {
        assertPrivateBroker(host);
        if (port == 1883) {
            throw new IllegalStateException("Refusing plaintext MQTT port 1883");
        }
        if (port != 8883) {
            throw new IllegalStateException("Live MQTT must use docker-network 8883");
        }
        if (!tls) {
            throw new IllegalStateException("Live MQTT requires TLS");
        }
    }

    public static void assertExactTopic(String topic) {
        if (topic == null || topic.isBlank() || topic.contains("+") || topic.contains("#")) {
            throw new IllegalArgumentException("Exact topics only");
        }
    }
}
