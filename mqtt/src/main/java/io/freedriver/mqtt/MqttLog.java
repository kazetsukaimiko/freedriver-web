package io.freedriver.mqtt;

import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * App MQTT logs. The logger name is {@code io.freedriver.mqtt}.
 * Lines never include a password, a payload, or an exception message.
 */
public final class MqttLog {

    public static final String NAME = "io.freedriver.mqtt";

    private static final Logger LOG = Logger.getLogger(NAME);

    private MqttLog() {
    }

    public static void connectFailed() {
        warn("mqtt connect failed reason=connect-failed");
    }

    public static void configUnreadable(String name) {
        warn("mqtt config unreadable name=" + oneLine(name, "-") + " reason=read-failed");
    }

    public static void connecting(String host, int port) {
        info("mqtt connecting host=" + oneLine(host, "-") + " port=" + port);
    }

    public static void connected(String host, int port, boolean reconnect) {
        info("mqtt connected host=" + oneLine(host, "-") + " port=" + port + " reconnect=" + reconnect);
    }

    public static void disconnected(String reason) {
        info("mqtt disconnected reason=" + oneLine(reason, "unknown"));
    }

    public static void resubscribed(List<String> topics) {
        StringBuilder joined = new StringBuilder();
        if (topics != null) {
            for (String topic : topics) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(oneLine(topic, "-"));
            }
        }
        info("mqtt resubscribed topics=" + joined);
    }

    public static void resubscribeFailed(String topic) {
        warn("mqtt resubscribe failed topic=" + oneLine(topic, "-") + " reason=subscribe-failed");
    }

    public static void stateReceived(UUID instanceId, int applianceCount) {
        debug("mqtt state received instance=" + instanceId + " appliances=" + applianceCount);
    }

    public static void stateSummary(UUID instanceId, long messages, String lastHeardAgo) {
        info("mqtt state summary instance=" + instanceId
                + " messages=" + messages
                + " lastHeardAgo=" + oneLine(lastHeardAgo, "never"));
    }

    public static void stateRejected(String topic, String reason) {
        warn("mqtt state rejected topic=" + oneLine(topic, "-") + " reason=" + oneLine(reason, "rejected"));
    }

    public static void commandSent(
            String commandId, UUID instanceId, String appliance, boolean state, String user) {
        info("mqtt command sent commandId=" + oneLine(commandId, "-")
                + " instance=" + instanceId
                + " appliance=" + oneLine(appliance, "-")
                + " state=" + state
                + " user=" + oneLine(user, "unknown"));
    }

    public static void commandUnacknowledged(
            String commandId, UUID instanceId, String appliance, boolean state, String user, long timeoutMs) {
        warn("mqtt command unacknowledged commandId=" + oneLine(commandId, "-")
                + " instance=" + instanceId
                + " appliance=" + oneLine(appliance, "-")
                + " state=" + state
                + " user=" + oneLine(user, "unknown")
                + " timeoutMs=" + timeoutMs);
    }

    public static void commandFailed(String commandId, UUID instanceId) {
        warn("mqtt command failed commandId=" + oneLine(commandId, "-")
                + " instance=" + instanceId
                + " reason=publish-failed");
    }

    private static void info(String message) {
        LOG.info(message);
    }

    private static void warn(String message) {
        LOG.warn(message);
    }

    private static void debug(String message) {
        LOG.debug(message);
    }

    /** One log line. Control characters cannot forge a second record. */
    private static String oneLine(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), 160));
        for (int i = 0; i < value.length() && out.length() < 160; i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                continue;
            }
            out.append(c);
        }
        String cleaned = out.toString().strip();
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
