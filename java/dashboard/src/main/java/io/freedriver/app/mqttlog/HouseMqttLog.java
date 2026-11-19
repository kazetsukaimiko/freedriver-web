package io.freedriver.app.mqttlog;

import io.freedriver.mqtt.MqttLog;

import java.util.logging.Logger;

/**
 * Appliance and house MQTT logs. The logger name is {@link MqttLog#NAME}.
 * Lines never include a password, a payload, or an exception message.
 * Every line is cleaned by {@link MqttLog#oneLine}.
 */
public final class HouseMqttLog {

    private static final Logger LOG = Logger.getLogger(MqttLog.NAME);

    private HouseMqttLog() {
    }

    public static void connectFailed() {
        warn("mqtt connect failed reason=" + MqttLog.oneLine("connect-failed", "connect-failed"));
    }

    public static void configUnreadable(String name) {
        warn("mqtt config unreadable name=" + MqttLog.oneLine(name, "-") + " reason=read-failed");
    }

    public static void stateReceived(java.util.UUID instanceId, int applianceCount) {
        debug("mqtt state received instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-")
                + " appliances=" + applianceCount);
    }

    public static void stateSummary(java.util.UUID instanceId, long messages, String lastHeardAgo) {
        info("mqtt state summary instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-")
                + " messages=" + messages
                + " lastHeardAgo=" + MqttLog.oneLine(lastHeardAgo, "never"));
    }

    public static void stateRejected(String topic, String reason) {
        warn("mqtt state rejected topic=" + MqttLog.oneLine(topic, "-")
                + " reason=" + MqttLog.oneLine(reason, "rejected"));
    }

    /** Keeping a house's topics subscribed. {@code outcome} is {@code subscribed}, {@code unsubscribed}, or {@code failed}. */
    public static void instanceSubscription(java.util.UUID instanceId, String outcome) {
        info("mqtt instance subscription instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-")
                + " outcome=" + MqttLog.oneLine(outcome, "-"));
    }

    /** The house list could not be read; the subscribed houses stay as they were. */
    public static void instancesRefreshFailed() {
        warn("mqtt instances refresh failed reason=" + MqttLog.oneLine("list-failed", "list-failed"));
    }

    public static void metricsReceived(java.util.UUID instanceId) {
        debug("mqtt metrics received instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-"));
    }

    public static void metricsRejected(String topic, String reason) {
        warn("mqtt metrics rejected topic=" + MqttLog.oneLine(topic, "-")
                + " reason=" + MqttLog.oneLine(reason, "rejected"));
    }

    public static void commandSent(
            String commandId, java.util.UUID instanceId, String appliance, boolean state, String user) {
        info("mqtt command sent commandId=" + MqttLog.oneLine(commandId, "-")
                + " instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-")
                + " appliance=" + MqttLog.oneLine(appliance, "-")
                + " state=" + state
                + " user=" + MqttLog.oneLine(user, "unknown"));
    }

    public static void commandUnacknowledged(
            String commandId, java.util.UUID instanceId, String appliance, boolean state, String user, long timeoutMs) {
        warn("mqtt command unacknowledged commandId=" + MqttLog.oneLine(commandId, "-")
                + " instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-")
                + " appliance=" + MqttLog.oneLine(appliance, "-")
                + " state=" + state
                + " user=" + MqttLog.oneLine(user, "unknown")
                + " timeoutMs=" + timeoutMs);
    }

    public static void commandFailed(String commandId, java.util.UUID instanceId) {
        warn("mqtt command failed commandId=" + MqttLog.oneLine(commandId, "-")
                + " instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-")
                + " reason=publish-failed");
    }

    /** House provision. {@code outcome} is {@code created}, {@code reset}, or {@code refused}. No password. */
    public static void house(java.util.UUID instanceId, String outcome) {
        info("mqtt house instance=" + MqttLog.oneLine(String.valueOf(instanceId), "-")
                + " outcome=" + MqttLog.oneLine(outcome, "-"));
    }

    private static void info(String message) {
        LOG.info(message);
    }

    private static void warn(String message) {
        LOG.warning(message);
    }

    private static void debug(String message) {
        LOG.fine(message);
    }
}
