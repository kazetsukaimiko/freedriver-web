package io.freedriver.mqtt;

/**
 * Connection target. Password is not a field here — it is supplied at connect time.
 */
public record MqttEndpoint(String host, int port, boolean tls, String username) {

    public MqttEndpoint {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("MQTT username required");
        }
        MqttBrokers.assertLiveBroker(host, port, tls);
    }
}
