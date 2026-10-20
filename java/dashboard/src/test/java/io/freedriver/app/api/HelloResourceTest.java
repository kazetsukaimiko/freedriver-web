package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class HelloResourceTest {
    @Test
    void testHelloEndpoint() {
        given()
                .when().get("/api/hello")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", is("Hello from Freedriver"))
                .body("service", is("freedriver-app"));
    }

}
