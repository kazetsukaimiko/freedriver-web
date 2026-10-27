package io.freedriver.app.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.freedriver.mqtt.MqttConnection;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One admin session on {@code $CONTROL/dynamic-security/v1}.
 * Command payloads are not logged. They carry client passwords.
 */
final class DynsecControl implements AutoCloseable {

    static final String COMMAND_TOPIC = "$CONTROL/dynamic-security/v1";
    static final String RESPONSE_TOPIC = "$CONTROL/dynamic-security/v1/response";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final MqttConnection connection;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkedBlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    DynsecControl(MqttConnection connection) {
        this.connection = connection;
    }

    void start() {
        connection.connect();
        connection.subscribe(RESPONSE_TOPIC, 1, (topic, payload) -> {
            try {
                inbox.add(mapper.readTree(payload));
            } catch (Exception ex) {
                inbox.add(mapper.createObjectNode().put("error", "unreadable dynamic security response"));
            }
        });
    }

    JsonNode command(ObjectNode command) {
        String correlation = UUID.randomUUID().toString();
        command.put("correlationData", correlation);
        ObjectNode root = mapper.createObjectNode();
        root.putArray("commands").add(command);
        final byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(root);
        } catch (Exception ex) {
            throw new IllegalStateException("dynamic security command could not be encoded");
        }
        connection.publish(COMMAND_TOPIC, payload, 1, false);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            long wait = deadline - System.nanoTime();
            if (wait <= 0) {
                throw new IllegalStateException("dynamic security command timed out");
            }
            final JsonNode message;
            try {
                message = inbox.poll(wait, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("dynamic security command interrupted");
            }
            if (message == null) {
                throw new IllegalStateException("dynamic security command timed out");
            }
            if (message.hasNonNull("error") && !message.has("responses")) {
                throw new IllegalStateException("dynamic security response was unreadable");
            }
            JsonNode responses = message.get("responses");
            if (responses == null || !responses.isArray()) {
                continue;
            }
            for (JsonNode item : responses) {
                JsonNode marker = item.get("correlationData");
                if (marker != null && correlation.equals(marker.asText())) {
                    return item;
                }
            }
        }
    }

    @Override
    public void close() {
        connection.close();
    }
}
