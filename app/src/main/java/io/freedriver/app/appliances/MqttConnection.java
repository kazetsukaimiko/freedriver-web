package io.freedriver.app.appliances;

/**
 * Tiny MQTT session for the live adapter. Not started unless
 * {@code freedriver.appliances.live-commands=true}.
 */
public interface MqttConnection {

    void connect();

    void subscribe(String topic, int qos, MessageHandler handler);

    void publish(String topic, String payload, int qos, boolean retain);

    void close();

    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String topic, String payload);
    }
}
