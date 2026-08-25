package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class HealthResourceTest {
    @Test
    void testHealthEndpoint() {
        String body = given()
                .when().get("/api/health")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", is("UP"))
                .body("build", nullValue())
                .body("$", not(hasKey("build")))
                .extract()
                .asString();
        assertFalse(body.contains("SNAPSHOT"));
    }
}
