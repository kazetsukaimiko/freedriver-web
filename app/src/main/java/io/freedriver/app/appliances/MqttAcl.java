package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;

/**
 * Opposite-ACL contract the broker must enforce (Techops / CI).
 * Not a live Mosquitto config.
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
        if (identity == Identity.AUTONOMY && ApplianceSchemas.STATE_TOPIC.equals(topic)) {
            return !retain;
        }
        if (identity == Identity.API && ApplianceSchemas.COMMAND_TOPIC.equals(topic)) {
            return !retain;
        }
        return false;
    }

    public static boolean canSubscribe(Identity identity, String topic) {
        if (identity == Identity.AUTONOMY) {
            return ApplianceSchemas.COMMAND_TOPIC.equals(topic);
        }
        if (identity == Identity.API) {
            return ApplianceSchemas.STATE_TOPIC.equals(topic);
        }
        return false;
    }
}
