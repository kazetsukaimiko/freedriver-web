package io.freedriver.app.appliances;

import io.freedriver.app.instances.HouseProvision;
import io.freedriver.app.instances.RecordingInstanceIdentity;
import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard starts before Keycloak answers: the startup load fails, the dashboard starts with only
 * the configured ids, and the first refresh that succeeds follows the houses.
 */
@QuarkusTest
@TestProfile(InstanceSubscriptionsStartupTest.KeycloakDownAtStart.class)
class InstanceSubscriptionsStartupTest {

    public static class KeycloakDownAtStart implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new HashMap<>(new InstanceSubscriptionsTest.StateOnly().getConfigOverrides());
            config.put("recording-identity.refuse-list-at-start", "true");
            return config;
        }
    }

    @Inject
    InstanceSubscriptions subscriptions;

    @Inject
    SubscribedHouses followed;

    @Inject
    MqttSettings settings;

    @Inject
    HouseProvision houses;

    @Inject
    RecordingInstanceIdentity identity;

    @Inject
    FixtureMqttConnection mqtt;

    @Inject
    MqttLiveClient client;

    @Inject
    ApplianceControl control;

    @Test
    void a_failed_startup_load_is_recovered_by_the_first_successful_refresh() {
        assertEquals(Set.copyOf(settings.configuredInstances()), followed.subscribed());
        assertFalse(client.brokerConnected());

        UUID house = UUID.fromString(houses.create("porch").instanceId());
        identity.refuseList(false);
        subscriptions.refresh();

        assertTrue(followed.contains(house));
        assertTrue(client.brokerConnected());
        mqtt.publishState(ApplianceSchemas.appliancesTopic(house), ApplianceJson.writeState(new ApplianceStateMessage(
                "Porch", null, List.of(new Appliance("lamp", true)))));
        assertTrue(control.instanceSnapshot(house).orElseThrow().find("lamp").orElseThrow().state());
    }
}
