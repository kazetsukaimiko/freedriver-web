package io.freedriver.app.appliances;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots with the prod config profile, which loads {@code application-prod.properties}.
 * OIDC stays off here so the test does not call Keycloak. live-commands is on.
 * The host ACL is absent, so the list is empty and nothing opens a broker.
 */
@QuarkusTest
@TestProfile(ProdMockAppliancesTest.ProdProfile.class)
class ProdMockAppliancesTest {

    public static class ProdProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.enabled", "false",
                    "freedriver.invite.client-secret", "unused-by-this-test",
                    "quarkus.http.test-port", "0");
        }
    }

    @Inject
    AppliancesConfig config;

    @Inject
    FixtureMqttConnection mqtt;

    @Test
    void prod_profile_does_not_serve_the_practice_list_or_open_a_broker() {
        assertTrue(config.enabled());
        assertFalse(config.mock());
        assertTrue(config.liveCommands());
        assertFalse(config.mockRefresh());
        assertFalse(mqtt.connected());
        assertTrue(mqtt.subscriptions().isEmpty());
        assertTrue(mqtt.published().isEmpty());
    }

    @Test
    @TestSecurity(user = "ada", roles = {"dashboard"})
    void appliances_list_is_empty_without_broker_state() {
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("instances.size()", is(0));
        assertFalse(mqtt.connected());
        assertTrue(mqtt.published().isEmpty());
    }
}
