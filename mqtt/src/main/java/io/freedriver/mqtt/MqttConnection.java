package io.freedriver.mqtt;

/**
 * One MQTT session. {@link #subscribe} and {@link #publish} carry raw payload bytes.
 * {@link #connected()} is the live state of the session, not whether one was opened.
 */
public interface MqttConnection {

    void connect();

    void subscribe(String topic, int qos, MessageHandler handler);

    /** Stops delivery for one exact topic and forgets it, so a reconnect does not subscribe it again. */
    void unsubscribe(String topic);

    /** True while the session is connected to the broker. */
    boolean connected();

    void publish(String topic, byte[] payload, int qos, boolean retain);

    void close();

    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String topic, byte[] payload);
    }
}
