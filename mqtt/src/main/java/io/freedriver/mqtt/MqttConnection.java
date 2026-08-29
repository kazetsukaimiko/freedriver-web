package io.freedriver.mqtt;

/**
 * Tiny MQTT session. Payloads are raw bytes; JSON codecs live in mqtt-contract.
 */
public interface MqttConnection {

    void connect();

    void subscribe(String topic, int qos, MessageHandler handler);

    void publish(String topic, byte[] payload, int qos, boolean retain);

    void close();

    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String topic, byte[] payload);
    }
}
