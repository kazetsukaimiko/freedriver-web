package io.freedriver.app.appliances;

import java.util.UUID;

/**
 * Opposite-ACL contract the broker must enforce (Techops / CI).
 * Not a live Mosquitto config.
 * Topics are {@code freedriver/v1/{instanceId}/appliances} and
 * {@code freedriver/v1/{instanceId}/commands}. {@code instanceName} is never an ACL.
 */
public final class MqttAcl {
    public enum Identity {
        AUTONOMY,
        API,
        ANONYMOUS,
        OTHER
    }

    private MqttAcl() {
    }

    public static boolean canPublish(Identity identity, String topic, boolean retain) {
        if (identity == Identity.AUTONOMY && isAppliancesTopic(topic)) {
            return !retain;
        }
        if (identity == Identity.API && isCommandsTopic(topic)) {
            return !retain;
        }
        return false;
    }

    public static boolean canSubscribe(Identity identity, String topic) {
        if (identity == Identity.AUTONOMY) {
            return isCommandsTopic(topic);
        }
        if (identity == Identity.API) {
            return isAppliancesTopic(topic);
        }
        return false;
    }

    static boolean isAppliancesTopic(String topic) {
        return instanceIdFrom(topic, "appliances") != null;
    }

    static boolean isCommandsTopic(String topic) {
        return instanceIdFrom(topic, "commands") != null;
    }

    static UUID instanceIdFrom(String topic, String lastSegment) {
        if (topic == null) {
            return null;
        }
        String prefix = "freedriver/v1/";
        String suffix = "/" + lastSegment;
        if (!topic.startsWith(prefix) || !topic.endsWith(suffix)) {
            return null;
        }
        String id = topic.substring(prefix.length(), topic.length() - suffix.length());
        if (id.isBlank() || id.contains("/")) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
