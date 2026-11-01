package io.freedriver.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class DaemonConfig {

    static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(10);
    static final Duration DEFAULT_WATCH = Duration.ofSeconds(1);

    private static final Set<String> KEYS = Set.of(
            "deviceId", "hostname", "agents", "secretFile", "allow", "grok", "sessionTimeout", "workDir",
            "mode", "router");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String deviceId;
    private final Path secretFile;
    private final DaemonSettings settings;

    private DaemonConfig(String deviceId, Path secretFile, DaemonSettings settings) {
        this.deviceId = deviceId;
        this.secretFile = secretFile;
        this.settings = settings;
    }

    String deviceId() {
        return deviceId;
    }

    Path secretFile() {
        return secretFile;
    }

    DaemonSettings settings() {
        return settings;
    }

    static DaemonConfig parse(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (IOException failed) {
            throw new IllegalArgumentException("config is not json");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("config is not json");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown config key " + key);
            }
        }
        String deviceId = text(node, "deviceId");
        if (deviceId.startsWith("-") || !deviceId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("device id is not safe");
        }
        WorkerMode mode = mode(node);
        if (mode == WorkerMode.OPENBOT) {
            if (!OpenBotWorker.DEVICE_ID.equals(deviceId)) {
                throw new IllegalArgumentException("openbot device id is freedriver-openbot");
            }
            if (node.has("grok") || node.has("allow")) {
                throw new IllegalArgumentException("grok settings belong to the device daemon");
            }
        }
        String hostname = text(node, "hostname");
        if (hostname.length() > 253) {
            throw new IllegalArgumentException("hostname is too long");
        }
        List<String> agents = strings(node.get("agents"), "agents");
        if (agents.isEmpty()) {
            throw new IllegalArgumentException("agents are required");
        }
        if (!node.hasNonNull("secretFile")) {
            throw new IllegalArgumentException("secretFile is required");
        }
        String secretPath = text(node, "secretFile");
        List<String> allow = node.has("allow") ? GrokCommand.sanitize(strings(node.get("allow"), "allow")) : List.of();
        String grok = node.has("grok") ? text(node, "grok") : (mode == WorkerMode.OPENBOT ? null : "grok");
        List<String> router = node.has("router") ? strings(node.get("router"), "router") : List.of();
        Duration timeout = node.has("sessionTimeout")
                ? duration(text(node, "sessionTimeout"))
                : DEFAULT_SESSION_TIMEOUT;
        Path workDir = node.has("workDir") ? Path.of(text(node, "workDir")) : null;
        return new DaemonConfig(
                deviceId,
                Path.of(secretPath),
                new DaemonSettings(hostname, agents, allow, grok, timeout, DEFAULT_WATCH, workDir, mode, router));
    }

    WorkerMode mode() {
        return settings.mode();
    }

    private static WorkerMode mode(JsonNode node) {
        if (!node.has("mode")) {
            return WorkerMode.GROK;
        }
        String value = text(node, "mode");
        if ("grok".equals(value)) {
            return WorkerMode.GROK;
        }
        if ("openbot".equals(value)) {
            return WorkerMode.OPENBOT;
        }
        throw new IllegalArgumentException("mode is grok or openbot");
    }

    private static Duration duration(String text) {
        try {
            Duration parsed = Duration.parse(text);
            if (parsed.isZero() || parsed.isNegative()) {
                throw new IllegalArgumentException("session timeout must be positive");
            }
            return parsed;
        } catch (DateTimeException failed) {
            throw new IllegalArgumentException("session timeout must be an ISO-8601 duration");
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode node, String key) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(key + " must be a list");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(key + " must be a list of text");
            }
            values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
