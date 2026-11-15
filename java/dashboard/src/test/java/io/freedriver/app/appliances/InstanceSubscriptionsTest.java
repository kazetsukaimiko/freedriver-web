package io.freedriver.app.appliances;

import io.freedriver.app.instances.HouseProvision;
import io.freedriver.app.instances.RecordingInstanceIdentity;
import io.freedriver.app.metrics.MetricsTopics;
import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The houses come from the Keycloak {@code mqtt-instance} list (#483): a house provisioned while the
 * dashboard runs is heard after one refresh, with no restart.
 */
@QuarkusTest
@TestProfile(InstanceSubscriptionsTest.StateOnly.class)
class InstanceSubscriptionsTest {

    /** Live state, no live commands, no configured ids, and no automatic refresh. */
    public static class StateOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.appliances.enabled", "true",
                    "freedriver.appliances.live-commands", "false",
                    "freedriver.appliances.mock", "false",
                    "freedriver.mqtt.host", "mosquitto",
                    "freedriver.mqtt.port", "8883",
                    "freedriver.mqtt.tls", "true",
                    "quarkus.http.test-port", "0");
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

    @Inject
    MqttLiveClient client;

    @Inject
    ApplianceControl control;

    @BeforeEach
    void reset() {
        identity.clear();
        subscriptions.refresh();
        mqtt.reset();
    }

    @Test
    void a_provisioned_house_receives_state_after_one_refresh_without_a_restart() {
        UUID house = UUID.fromString(houses.create("porch").instanceId());
        String state = ApplianceSchemas.appliancesTopic(house);
        assertFalse(subscriptions.contains(house), "the refresh switch is off in this profile");
        assertFalse(mqtt.subscriptions().contains(state));

        subscriptions.refresh();

        assertTrue(subscriptions.contains(house));
        assertTrue(mqtt.subscriptions().containsAll(List.of(state, MetricsTopics.metricsTopic(house))),
                mqtt.subscriptions()::toString);
        mqtt.publishState(state, ApplianceJson.writeState(new ApplianceStateMessage(
                "Porch", null, List.of(new Appliance("lamp", true)))));
        assertTrue(control.instanceSnapshot(house).orElseThrow().find("lamp").orElseThrow().state());
    }

    @Test
    void a_removed_house_is_unsubscribed_and_its_state_refused() {
        UUID house = UUID.fromString(houses.create("shed").instanceId());
        subscriptions.refresh();
        String state = ApplianceSchemas.appliancesTopic(house);
        assertTrue(mqtt.subscriptions().contains(state));

        identity.remove(house.toString());
        subscriptions.refresh();

        assertFalse(subscriptions.contains(house));
        assertFalse(mqtt.subscriptions().contains(state), mqtt.subscriptions()::toString);
        assertFalse(mqtt.subscriptions().contains(MetricsTopics.metricsTopic(house)), mqtt.subscriptions()::toString);
        assertThrows(IllegalStateException.class, () -> mqtt.publishState(state, "{}"));
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            client.onBrokerMessage(state, ApplianceJson.writeState(new ApplianceStateMessage(
                    "Shed", null, List.of(new Appliance("lamp", true)))).getBytes(StandardCharsets.UTF_8));
            assertTrue(logs.find("reason=unknown-instance").contains("topic=" + state), logs.all());
        }
        assertTrue(control.instanceSnapshot(house).isEmpty());
    }

    @Test
    void a_failed_refresh_keeps_the_current_set() {
        UUID kept = UUID.fromString(houses.create("barn").instanceId());
        subscriptions.refresh();
        assertEquals(Set.of(kept), subscriptions.subscribed());

        identity.remove(kept.toString());
        identity.refuseList(true);
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            subscriptions.refresh();
            assertTrue(logs.all().contains("mqtt instances refresh failed"), logs.all());
        }

        assertEquals(Set.of(kept), subscriptions.subscribed());
        assertTrue(mqtt.subscriptions().contains(ApplianceSchemas.appliancesTopic(kept)));

        identity.refuseList(false);
        subscriptions.refresh();
        assertTrue(subscriptions.subscribed().isEmpty());
    }

    @Test
    void a_failed_state_write_is_logged_and_the_next_message_is_handled() {
        UUID house = UUID.fromString(houses.create("attic").instanceId());
        subscriptions.refresh();
        String state = ApplianceSchemas.appliancesTopic(house);
        FailingStateStore.failing.set(true);
        try (MqttLogCapture logs = MqttLogCapture.open()) {
            mqtt.publishState(state, ApplianceJson.writeState(new ApplianceStateMessage(
                    "Attic", null, List.of(new Appliance("fan", true)))));
            assertTrue(logs.find("reason=store-failed").contains("topic=" + state), logs.all());
        } finally {
            FailingStateStore.failing.set(false);
        }
        mqtt.publishState(state, ApplianceJson.writeState(new ApplianceStateMessage(
                "Attic", null, List.of(new Appliance("fan", false)))));
        assertFalse(control.instanceSnapshot(house).orElseThrow().find("fan").orElseThrow().state());
    }

    @Test
    @TestSecurity(user = "mcp-reader", roles = "mcp-user")
    void home_status_reports_the_broker_down_when_the_session_drops() {
        houses.create("cellar");
        subscriptions.refresh();
        assertTrue(client.brokerConnected());
        given().get("/api/mcp/home-status").then().statusCode(200).body("brokerConnected", is(true));

        mqtt.drop();

        assertFalse(client.brokerConnected());
        given().get("/api/mcp/home-status").then().statusCode(200).body("brokerConnected", is(false));
        given().get("/api/mcp/debug").then().statusCode(200).body("errorCategory", is("broker-down"));
    }
}
