package io.freedriver.mqtt.paho;

import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.MqttEndpoint;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production MQTTS client. TCP host is docker-network {@code mosquitto}, never mqtt.freedriver.io.
 */
public final class PahoMqttConnection implements MqttConnection {

    private static final Logger LOG = Logger.getLogger(PahoMqttConnection.class.getName());

    private final MqttEndpoint endpoint;
    private final String password;
    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();
    private MqttClient client;

    public PahoMqttConnection(MqttEndpoint endpoint, String password) {
        this.endpoint = endpoint;
        this.password = password == null ? "" : password;
    }

    @Override
    public void connect() {
        String uri = (endpoint.tls() ? "ssl://" : "tcp://") + endpoint.host() + ":" + endpoint.port();
        try {
            client = new MqttClient(uri, "freedriver-api-" + UUID.randomUUID(), new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    LOG.log(Level.WARNING, "live MQTT connection lost", cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    MessageHandler handler = handlers.get(topic);
                    if (handler == null) {
                        return;
                    }
                    handler.onMessage(topic, message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setUserName(endpoint.username());
            if (!password.isEmpty()) {
                options.setPassword(password.toCharArray());
            }
            if (endpoint.tls()) {
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
        MqttBrokers.assertExactTopic(topic);
        handlers.put(topic, handler);
        try {
            client.subscribe(topic, qos);
        } catch (MqttException e) {
            throw new IllegalStateException("Live MQTT subscribe failed", e);
        }
    }

    @Override
    public void publish(String topic, byte[] payload, int qos, boolean retain) {
        if (retain) {
            throw new IllegalArgumentException("retain must be false");
        }
        MqttBrokers.assertExactTopic(topic);
        try {
            MqttMessage message = new MqttMessage(payload);
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
            LOG.log(Level.WARNING, "live MQTT disconnect failed", e);
        }
        try {
            current.close();
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "live MQTT close failed", e);
        }
    }
}
