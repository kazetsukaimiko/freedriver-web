package io.freedriver.mqtt.paho;

import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.MqttEndpoint;
import io.freedriver.mqtt.MqttLog;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Eclipse Paho {@link MqttConnection} for a {@link MqttEndpoint}.
 * TLS checks the broker hostname. The client id is {@code freedriver-api-} plus a random UUID.
 */
public final class PahoMqttConnection implements MqttConnection {

    private final MqttEndpoint endpoint;
    private final String password;
    private final Map<String, Subscription> handlers = new ConcurrentHashMap<>();
    private final ExecutorService resubscribeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mqtt-resubscribe");
        thread.setDaemon(true);
        return thread;
    });
    private volatile MqttClient client;
    private volatile boolean closing;

    public PahoMqttConnection(MqttEndpoint endpoint, String password) {
        this.endpoint = endpoint;
        this.password = password == null ? "" : password;
    }

    @Override
    public void connect() {
        announceConnecting();
        String uri = (endpoint.tls() ? "ssl://" : "tcp://") + endpoint.host() + ":" + endpoint.port();
        try {
            client = new MqttClient(uri, "freedriver-api-" + UUID.randomUUID(), new MemoryPersistence());
            client.setCallback(new Callbacks(
                    endpoint.host(),
                    endpoint.port(),
                    handlers,
                    this::subscribeTopic,
                    resubscribeExecutor::execute,
                    () -> !closing));
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

    void announceConnecting() {
        MqttLog.connecting(endpoint.host(), endpoint.port());
    }

    @Override
    public void subscribe(String topic, int qos, MessageHandler handler) {
        MqttBrokers.assertExactTopic(topic);
        handlers.put(topic, new Subscription(qos, handler));
        subscribeTopic(topic, qos);
    }

    private void subscribeTopic(String topic, int qos) {
        MqttClient current = client;
        if (current == null) {
            throw new IllegalStateException("Live MQTT subscribe failed");
        }
        try {
            current.subscribe(topic, qos);
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
        MqttClient current = client;
        if (current == null) {
            throw new IllegalStateException("Live MQTT publish failed");
        }
        try {
            MqttMessage message = new MqttMessage(payload);
            message.setQos(qos);
            message.setRetained(false);
            current.publish(topic, message);
        } catch (MqttException e) {
            throw new IllegalStateException("Live MQTT publish failed", e);
        }
    }

    @Override
    public void close() {
        closing = true;
        resubscribeExecutor.shutdownNow();
        MqttClient current = client;
        client = null;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnect();
                MqttLog.disconnected("closed");
            }
        } catch (MqttException e) {
            MqttLog.disconnected(disconnectReason(e));
        }
        try {
            current.close();
        } catch (MqttException ignored) {
            // Already logged the disconnect. The cause text can echo the broker URI.
        }
    }

    static String disconnectReason(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        if (cause instanceof MqttException mqtt) {
            return "MqttException:" + mqtt.getReasonCode();
        }
        String type = cause.getClass().getSimpleName();
        if (type == null || type.isBlank()) {
            return "exception";
        }
        return type;
    }

    record Subscription(int qos, MessageHandler handler) {
    }

    @FunctionalInterface
    interface TopicSubscriber {
        void subscribe(String topic, int qos) throws MqttException;
    }

    /**
     * Paho calls {@code connectComplete} for the first session and every automatic reconnect.
     * Clean session drops broker subscriptions, so a reconnect subscribes to the stored topics again.
     */
    static final class Callbacks implements MqttCallbackExtended {

        private final String host;
        private final int port;
        private final Map<String, Subscription> handlers;
        private final TopicSubscriber subscriber;
        private final Consumer<Runnable> schedule;
        private final java.util.function.BooleanSupplier acceptLoss;

        Callbacks(
                String host,
                int port,
                Map<String, Subscription> handlers,
                TopicSubscriber subscriber,
                Consumer<Runnable> schedule,
                java.util.function.BooleanSupplier acceptLoss) {
            this.host = host;
            this.port = port;
            this.handlers = handlers;
            this.subscriber = subscriber;
            this.schedule = schedule;
            this.acceptLoss = acceptLoss;
        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            // serverURI is not logged. Paho may put userinfo in it; host and port come from the endpoint.
            MqttLog.connected(host, port, reconnect);
            if (!reconnect) {
                return;
            }
            // connectComplete runs on Paho's comms thread, and subscribe() waits for that thread.
            try {
                schedule.accept(this::resubscribe);
            } catch (RejectedExecutionException ex) {
                MqttLog.resubscribeFailed("-");
            }
        }

        void resubscribe() {
            List<String> topics = new ArrayList<>(handlers.keySet());
            Collections.sort(topics);
            List<String> restored = new ArrayList<>();
            for (String topic : topics) {
                Subscription subscription = handlers.get(topic);
                if (subscription == null) {
                    continue;
                }
                try {
                    subscriber.subscribe(topic, subscription.qos());
                    restored.add(topic);
                } catch (Exception ex) {
                    MqttLog.resubscribeFailed(topic);
                }
            }
            MqttLog.resubscribed(restored);
        }

        @Override
        public void connectionLost(Throwable cause) {
            if (!acceptLoss.getAsBoolean()) {
                return;
            }
            MqttLog.disconnected(disconnectReason(cause));
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            Subscription subscription = handlers.get(topic);
            if (subscription == null || subscription.handler() == null) {
                return;
            }
            byte[] payload = message.getPayload();
            subscription.handler().onMessage(topic, payload == null ? new byte[0] : payload);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    }
}
