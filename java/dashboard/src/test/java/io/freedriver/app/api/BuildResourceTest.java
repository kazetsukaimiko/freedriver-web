package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class BuildResourceTest {
    @Test
    void testBuildEndpoint() {
        given()
                .when().get("/api/build")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("build", is("1.0.0-SNAPSHOT"));
    }
}
