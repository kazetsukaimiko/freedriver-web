package io.freedriver.app.api;

import io.freedriver.app.appliances.MockAutonomy;
import io.freedriver.autonomy.mqtt.contract.Appliance;
import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AppliancesDisabledTest.DisabledProfile.class)
class AppliancesDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.appliances.enabled", "false",
                    "freedriver.appliances.live-commands", "false",
                    "freedriver.appliances.backend", "none",
                    "quarkus.http.test-port", "0");
        }
    }

    @Inject
    MockAutonomy mock;

    @Inject
    Event<ApplianceCommandMessage> commands;

    @Inject
    Event<ApplianceStateMessage> states;

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void get_is_404_when_disabled() {
        given().when().get("/api/appliances").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void post_is_404_when_disabled() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/hallway")
                .then().statusCode(404);
    }

    @Test
    void hello_still_public() {
        given().when().get("/api/hello").then().statusCode(200);
    }

    @Test
    void build_still_public() {
        given().when().get("/api/build").then().statusCode(200);
    }

    @Test
    void mock_is_inert_when_backend_none() {
        mock.reset();
        states.fire(new ApplianceStateMessage(
                MockAutonomy.INSTANCE_ID,
                MockAutonomy.INSTANCE_NAME,
                null,
                List.of(new Appliance("hallway", false))));
        commands.fire(new ApplianceCommandMessage(
                MockAutonomy.INSTANCE_ID, "cmd-off", "hallway", true));
        assertTrue(mock.publishedCommands().isEmpty());
        assertTrue(mock.snapshot().isEmpty());
    }
}
