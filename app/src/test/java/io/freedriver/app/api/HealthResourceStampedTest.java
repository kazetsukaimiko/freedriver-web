package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@TestProfile(HealthResourceStampedTest.StampedProfile.class)
class HealthResourceStampedTest {

    public static class StampedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.application.version", "2026-08_r184",
                    "quarkus.http.test-port", "0");
        }
    }

    @Test
    void exposesMatchingApplicationVersionAsBuild() {
        given()
                .when().get("/api/health")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", is("UP"))
                .body("build", is("2026-08_r184"));
    }
}
