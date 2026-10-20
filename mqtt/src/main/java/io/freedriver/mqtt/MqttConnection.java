package io.freedriver.mqtt;

/**
 * One MQTT session. {@link #subscribe} and {@link #publish} carry raw payload bytes.
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
