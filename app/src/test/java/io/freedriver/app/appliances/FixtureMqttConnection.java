package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.mqtt.MqttConnection;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** CI fixture publisher. Replaces the Paho client in tests. Not a broker. */
@Mock
@ApplicationScoped
public class FixtureMqttConnection implements MqttConnection {

    public record Published(String topic, byte[] payload, int qos, boolean retain) {
        String payloadUtf8() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final List<Published> published = new CopyOnWriteArrayList<>();
    private volatile boolean connected;

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public void subscribe(String topic, int qos, MessageHandler handler) {
        MqttBrokers.assertExactTopic(topic);
        handlers.put(topic, handler);
    }

    @Override
    public void publish(String topic, byte[] payload, int qos, boolean retain) {
        published.add(new Published(topic, payload, qos, retain));
    }

    @Override
    public void close() {
        connected = false;
        handlers.clear();
    }

    public boolean connected() {
        return connected;
    }

    public List<Published> published() {
        return List.copyOf(published);
    }

    public void reset() {
        published.clear();
        connected = true;
    }

    public void publishState(String topic, String payload) {
        MessageHandler handler = handlers.get(topic);
        if (handler == null) {
            throw new IllegalStateException("no subscriber for " + topic);
        }
        handler.onMessage(topic, payload.getBytes(StandardCharsets.UTF_8));
    }

    public List<String> subscriptions() {
        return new ArrayList<>(handlers.keySet());
    }
}
