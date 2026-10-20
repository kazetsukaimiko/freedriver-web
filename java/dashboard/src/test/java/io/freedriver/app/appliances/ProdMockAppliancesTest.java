package io.freedriver.app.appliances;

import io.freedriver.app.security.CsrfFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots with the prod config profile, which loads {@code application-prod.properties}.
 * OIDC stays off here so the test does not call Keycloak. Appliance keys are not overridden.
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
                    "quarkus.http.test-port", "0");
        }
    }

    @Inject
    AppliancesConfig config;

    @Inject
    FixtureMqttConnection mqtt;

    @Inject
    MockAutonomy mock;

    @Test
    void prod_profile_serves_mock_with_no_broker_connection() {
        assertTrue(config.enabled());
        assertTrue(config.mock());
        assertFalse(config.liveCommands());
        assertTrue(config.mockRefresh());
        assertFalse(mqtt.connected());
        assertTrue(mqtt.subscriptions().isEmpty());
        assertTrue(mqtt.published().isEmpty());
    }

    @Test
    @TestSecurity(user = "ada", roles = {"dashboard"})
    void switch_changes_mock_state_and_does_not_publish() {
        String name = MockAutonomy.FIXTURE_NAMES.getFirst();
        var map = given().when().get("/api/appliances");
        map.then()
                .statusCode(200)
                .body("instances.size()", is(1))
                .body("instances[0].instanceName", is(MockAutonomy.INSTANCE_NAME))
                .body("instances[0].appliances.size()", is(6))
                .body("instances[0].appliances.applianceName", hasItems(MockAutonomy.FIXTURE_NAMES.toArray(String[]::new)))
                .body("instances[0].appliances.applianceName", everyItem(endsWith(MockAutonomy.TEST_SUFFIX)));

        given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, map.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, map.path("csrfToken"))
                .body("{\"on\":true}")
                .when().post("/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + name)
                .then()
                .statusCode(200)
                .body("timeout", is(false))
                .body("appliances.find { it.applianceName == '" + name + "' }.on", is(true));

        assertFalse(mock.publishedCommands().isEmpty());
        assertFalse(mqtt.connected());
        assertTrue(mqtt.subscriptions().isEmpty());
        assertTrue(mqtt.published().isEmpty());
    }
}
