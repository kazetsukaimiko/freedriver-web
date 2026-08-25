package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

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

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void get_is_404_when_disabled() {
        given().when().get("/api/appliances").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void post_is_404_when_disabled() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/living-room-lamp")
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
}
