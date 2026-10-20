package io.freedriver.mqtt.paho;

import io.freedriver.mqtt.MqttEndpoint;
import io.freedriver.mqtt.MqttLog;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PahoMqttConnectionTest {

    private static final String TOPIC = "freedriver/v1/550e8400-e29b-41d4-a716-446655440000/appliances";
    private static final String SECRET = "super-secret-pw";

    @Test
    void connect_announces_before_the_client_and_omits_the_password() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/freedriver/mqtt/paho/PahoMqttConnection.java"));
        int connect = source.indexOf("public void connect()");
        int announce = source.indexOf("announceConnecting();", connect);
        int clientConnect = source.indexOf("client.connect(options)", announce);
        assertTrue(connect >= 0 && announce > connect && clientConnect > announce, source);
        assertTrue(source.contains("implements MqttCallbackExtended"), source);
        assertTrue(source.contains("connectComplete"), source);

        PahoMqttConnection connection = new PahoMqttConnection(
                new MqttEndpoint("mosquitto", 8883, true, "api"), SECRET);
        try (LogLines logs = LogLines.open()) {
            connection.announceConnecting();
            assertEquals("mqtt connecting host=mosquitto port=8883", logs.all());
            assertFalse(logs.all().contains(SECRET), logs.all());
        } finally {
            connection.close();
        }
    }

    @Test
    void reconnect_resubscribes_off_the_callback_and_logs_it() {
        Map<String, PahoMqttConnection.Subscription> handlers = new ConcurrentHashMap<>();
        List<String> subscribed = new CopyOnWriteArrayList<>();
        List<Integer> qos = new CopyOnWriteArrayList<>();
        AtomicInteger inline = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        handlers.put(TOPIC, new PahoMqttConnection.Subscription(1, (topic, payload) -> {
        }));
        PahoMqttConnection.Callbacks callbacks = new PahoMqttConnection.Callbacks(
                "mosquitto",
                8883,
                handlers,
                (topic, quality) -> {
                    inline.incrementAndGet();
                    subscribed.add(topic);
                    qos.add(quality);
                },
                queued::add,
                () -> true);

        try (LogLines logs = LogLines.open()) {
            callbacks.connectComplete(false, "ssl://api:" + SECRET + "@mosquitto:8883");
            assertTrue(queued.isEmpty());
            assertEquals(0, inline.get());
            assertTrue(logs.all().contains("mqtt connected host=mosquitto port=8883 reconnect=false"), logs.all());
            assertFalse(logs.all().contains("mqtt resubscribed"), logs.all());
            assertFalse(logs.all().contains(SECRET), logs.all());

            callbacks.connectComplete(true, "ssl://api:" + SECRET + "@mosquitto:8883");
            assertEquals(1, queued.size());
            assertEquals(0, inline.get(), "resubscribe waits until it is off the comms thread");
            assertTrue(logs.all().contains("mqtt connected host=mosquitto port=8883 reconnect=true"), logs.all());
            queued.removeFirst().run();
            assertEquals(List.of(TOPIC), subscribed);
            assertEquals(List.of(1), qos);
            assertTrue(logs.all().contains("mqtt resubscribed topics=" + TOPIC), logs.all());
            assertFalse(logs.all().contains(SECRET), logs.all());
        }
    }

    @Test
    void disconnect_logs_a_short_reason_not_the_exception_text() {
        PahoMqttConnection.Callbacks callbacks = callbacks(new ConcurrentHashMap<>(), (topic, qos) -> {
        });
        try (LogLines logs = LogLines.open()) {
            callbacks.connectionLost(null);
            callbacks.connectionLost(new RuntimeException(SECRET));
            callbacks.connectionLost(new MqttException(32109));
            String all = logs.all();
            assertTrue(all.contains("mqtt disconnected reason=unknown"), all);
            assertTrue(all.contains("mqtt disconnected reason=RuntimeException"), all);
            assertTrue(all.contains("mqtt disconnected reason=MqttException:32109"), all);
            assertFalse(all.contains(SECRET), all);
        }
    }

    @Test
    void resubscribe_failure_logs_the_topic_not_the_cause() {
        Map<String, PahoMqttConnection.Subscription> handlers = new ConcurrentHashMap<>();
        handlers.put(TOPIC, new PahoMqttConnection.Subscription(1, (topic, payload) -> {
        }));
        PahoMqttConnection.Callbacks callbacks = callbacks(handlers, (topic, qos) -> {
            throw new MqttException(new IllegalStateException(SECRET));
        });
        try (LogLines logs = LogLines.open()) {
            callbacks.resubscribe();
            String all = logs.all();
            assertTrue(all.contains("mqtt resubscribe failed topic=" + TOPIC + " reason=subscribe-failed"), all);
            assertTrue(all.contains("mqtt resubscribed topics="), all);
            assertFalse(all.contains(TOPIC + ","), all);
            assertFalse(all.contains(SECRET), all);
            assertFalse(all.contains("IllegalStateException"), all);
        }
    }

    @Test
    void message_dispatch_does_not_log_the_payload() {
        AtomicReference<byte[]> got = new AtomicReference<>();
        Map<String, PahoMqttConnection.Subscription> handlers = new ConcurrentHashMap<>();
        handlers.put(TOPIC, new PahoMqttConnection.Subscription(1, (topic, payload) -> got.set(payload)));
        PahoMqttConnection.Callbacks callbacks = callbacks(handlers, (topic, qos) -> {
        });
        byte[] body = "PAYLOAD_MARKER".getBytes(StandardCharsets.UTF_8);
        try (LogLines logs = LogLines.open()) {
            callbacks.messageArrived(TOPIC, new MqttMessage(body));
            assertEquals("PAYLOAD_MARKER", new String(got.get(), StandardCharsets.UTF_8));
            assertFalse(logs.all().contains("PAYLOAD_MARKER"), logs.all());
        }
    }

    @Test
    void closed_connection_does_not_also_log_connection_lost() {
        PahoMqttConnection.Callbacks callbacks = new PahoMqttConnection.Callbacks(
                "mosquitto", 8883, new ConcurrentHashMap<>(), (topic, qos) -> {
                }, runnable -> {
                }, () -> false);
        try (LogLines logs = LogLines.open()) {
            callbacks.connectionLost(new RuntimeException(SECRET));
            assertEquals("", logs.all());
        }
    }

    private static PahoMqttConnection.Callbacks callbacks(
            Map<String, PahoMqttConnection.Subscription> handlers,
            PahoMqttConnection.TopicSubscriber subscriber) {
        return new PahoMqttConnection.Callbacks(
                "mosquitto", 8883, handlers, subscriber, runnable -> {
                }, () -> true);
    }
}
