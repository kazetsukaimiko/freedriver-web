package io.freedriver.app.appliances;

import io.freedriver.app.instances.HouseProvision;
import io.freedriver.app.instances.RecordingInstanceIdentity;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** With {@code freedriver.mqtt.refresh} on, provisioning a house subscribes this replica at once. */
@QuarkusTest
@TestProfile(InstanceSubscriptionsOnProvisionTest.RefreshOn.class)
class InstanceSubscriptionsOnProvisionTest {

    public static class RefreshOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new HashMap<>(new InstanceSubscriptionsTest.StateOnly().getConfigOverrides());
            config.put("freedriver.mqtt.refresh", "true");
            return config;
        }
    }

    @Inject
    InstanceSubscriptions subscriptions;

    @Inject
    HouseProvision houses;

    @Inject
    RecordingInstanceIdentity identity;

    @Inject
    FixtureMqttConnection mqtt;

    @Test
    void provisioning_a_house_subscribes_it_without_waiting_for_the_schedule() {
        identity.clear();
        UUID house = UUID.fromString(houses.create("garage").instanceId());

        assertTrue(subscriptions.contains(house));
        assertTrue(mqtt.subscriptions().contains(ApplianceSchemas.appliancesTopic(house)), mqtt.subscriptions()::toString);
    }
}
