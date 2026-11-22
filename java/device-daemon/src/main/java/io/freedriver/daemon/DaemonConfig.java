package io.freedriver.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DaemonConfig {

    static final Duration DEFAULT_WATCH = Duration.ofSeconds(1);

    private static final Set<String> OPENBOT_KEYS = Set.of(
            "deviceId", "hostname", "agents", "secretFile", "mode", "automationDir");
    private static final Set<String> OPENBOT_REFUSED = Set.of("router", "sessionTimeout", "workDir", "grok", "allow");
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
        return parse(json, System.getenv(), System.getProperty("user.home"));
    }

    static DaemonConfig parse(String json, Map<String, String> env, String home) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (IOException failed) {
            throw new IllegalArgumentException("config is not json");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("config is not json");
        }
        requireOpenBot(node);
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (OPENBOT_REFUSED.contains(key)) {
                throw new IllegalArgumentException(key + " is not an openbot mode setting");
            }
            if (!OPENBOT_KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown config key " + key);
            }
        }
        String deviceId = text(node, "deviceId");
        if (deviceId.startsWith("-") || !deviceId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("device id is not safe");
        }
        if (!OpenBotWorker.DEVICE_ID.equals(deviceId)) {
            throw new IllegalArgumentException("openbot device id is freedriver-openbot, not " + deviceId);
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
        Path secretFile = Path.of(text(node, "secretFile"));
        checkConnectorAgents(agents);
        Path automationDir = node.has("automationDir")
                ? Path.of(text(node, "automationDir"))
                : defaultAutomationDir(env, home);
        return new DaemonConfig(deviceId, secretFile, DaemonSettings.openBot(hostname, agents, automationDir));
    }

    /**
     * Mode is the first check, before keys and the device id. {@code grok} and a missing mode are
     * retired. Any other value is a config error.
     */
    private static void requireOpenBot(JsonNode node) {
        if (!node.has("mode")) {
            throw new RetiredModeException();
        }
        JsonNode value = node.get("mode");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("mode is required");
        }
        String mode = value.asText();
        if ("grok".equals(mode)) {
            throw new RetiredModeException();
        }
        if (!"openbot".equals(mode)) {
            throw new IllegalArgumentException("mode must be openbot");
        }
    }

    /** At most 32 names, each at most 64 characters, no two equal without regard to case. */
    static void checkConnectorAgents(List<String> agents) {
        if (agents.size() > OpenBotWorker.MAX_BOTS) {
            throw new IllegalArgumentException("agents has more than " + OpenBotWorker.MAX_BOTS + " names");
        }
        for (int i = 0; i < agents.size(); i++) {
            String agent = agents.get(i);
            if (agent.length() > OpenBotWorker.MAX_NAME) {
                throw new IllegalArgumentException("agents has a name longer than " + OpenBotWorker.MAX_NAME + " characters");
            }
            for (int j = 0; j < i; j++) {
                if (agents.get(j).equalsIgnoreCase(agent)) {
                    throw new IllegalArgumentException("agents names " + agent + " twice, without regard to case");
                }
            }
        }
    }

    /** {@code $XDG_CONFIG_HOME/OpenBot/automation}, else {@code ~/.config/OpenBot/automation}. */
    static Path defaultAutomationDir(Map<String, String> env, String home) {
        String xdg = env == null ? null : env.get("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg, "OpenBot", "automation");
        }
        return Path.of(home, ".config", "OpenBot", "automation");
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
