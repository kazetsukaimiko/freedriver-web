package io.freedriver.app.appliances;

import io.quarkus.logging.Log;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production MQTTS client. Not a CDI bean unless live-commands is on.
 * TCP host is docker-network {@code mosquitto}, never mqtt.freedriver.io.
 */
public final class PahoMqttConnection implements MqttConnection {

    private final MqttConfig config;
    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();
    private MqttClient client;

    public PahoMqttConnection(MqttConfig config) {
        this.config = config;
    }

    @Override
    public void connect() {
        MqttLiveRoute.assertLiveBroker(config.host(), config.port(), config.tls());
        String uri = (config.tls() ? "ssl://" : "tcp://") + config.host() + ":" + config.port();
        try {
            client = new MqttClient(uri, "freedriver-api-" + UUID.randomUUID(), new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    Log.warn("live MQTT connection lost", cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    MessageHandler handler = handlers.get(topic);
                    if (handler == null) {
                        return;
                    }
                    handler.onMessage(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setUserName(config.username());
            if (!config.password().isEmpty()) {
                options.setPassword(config.password().toCharArray());
            }
            if (config.tls()) {
                options.setSocketFactory(SSLContext.getDefault().getSocketFactory());
                options.setHttpsHostnameVerificationEnabled(true);
            }
            client.connect(options);
        } catch (Exception e) {
            close();
            throw new IllegalStateException("Live MQTT connect failed", e);
        }
    }

    @Override
    public void subscribe(String topic, int qos, MessageHandler handler) {
        if (topic.contains("+") || topic.contains("#")) {
            throw new IllegalArgumentException("Exact topics only");
        }
        handlers.put(topic, handler);
        try {
            client.subscribe(topic, qos);
        } catch (MqttException e) {
            throw new IllegalStateException("Live MQTT subscribe failed", e);
        }
    }

    @Override
    public void publish(String topic, String payload, int qos, boolean retain) {
        if (retain) {
            throw new IllegalArgumentException("retain must be false");
        }
        if (topic.contains("+") || topic.contains("#")) {
            throw new IllegalArgumentException("Exact topics only");
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(false);
            client.publish(topic, message);
        } catch (MqttException e) {
            throw new IllegalStateException("Live MQTT publish failed", e);
        }
    }

    @Override
    public void close() {
        MqttClient current = client;
        client = null;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnect();
            }
        } catch (MqttException e) {
            Log.warn("live MQTT disconnect failed", e);
        }
        try {
            current.close();
        } catch (MqttException e) {
            Log.warn("live MQTT close failed", e);
        }
    }
}
