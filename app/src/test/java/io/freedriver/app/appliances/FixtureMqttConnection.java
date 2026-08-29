package io.freedriver.app.appliances;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** CI fixture publisher. Replaces the Paho client in tests. Not a broker. */
@Mock
@ApplicationScoped
public class FixtureMqttConnection implements MqttConnection {

    public record Published(String topic, String payload, int qos, boolean retain) {}

    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final List<Published> published = new CopyOnWriteArrayList<>();
    private volatile boolean connected;

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public void subscribe(String topic, int qos, MessageHandler handler) {
        if (topic.contains("+") || topic.contains("#")) {
            throw new IllegalArgumentException("Exact topics only");
        }
        handlers.put(topic, handler);
    }

    @Override
    public void publish(String topic, String payload, int qos, boolean retain) {
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

    /** Fixture autonomy: publish Topic A into the live adapter. */
    public void publishState(String topic, String payload) {
        MessageHandler handler = handlers.get(topic);
        if (handler == null) {
            throw new IllegalStateException("no subscriber for " + topic);
        }
        handler.onMessage(topic, payload);
    }

    public List<String> subscriptions() {
        return new ArrayList<>(handlers.keySet());
    }
}
