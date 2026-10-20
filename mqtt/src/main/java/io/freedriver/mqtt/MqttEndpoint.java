package io.freedriver.mqtt;

/**
 * Live broker target: host, port, TLS, and username.
 */
public record MqttEndpoint(String host, int port, boolean tls, String username) {

    public MqttEndpoint {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("MQTT username required");
        }
        MqttBrokers.assertLiveBroker(host, port, tls);
    }
}
