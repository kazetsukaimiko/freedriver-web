package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.mqtt.contract.ApplianceCommandMessage;

/**
 * App-side command mint helper. Broker rules live in {@link MqttBrokers}.
 */
public final class MqttLiveRoute {

    private MqttLiveRoute() {
    }

    public static void assertPrivateBroker(String host) {
        MqttBrokers.assertPrivateBroker(host);
    }

    public static void assertLiveBroker(String host, int port, boolean tls) {
        MqttBrokers.assertLiveBroker(host, port, tls);
    }

    public static ApplianceCommandMessage command(String commandId, String applianceName, boolean state) {
        return new ApplianceCommandMessage(commandId, applianceName, state);
    }
}
