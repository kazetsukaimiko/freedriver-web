package io.freedriver.dispatcher;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class DispatcherResourceTest {

    @Test
    void anonymous_submit_is_rejected() {
        given()
                .contentType("application/json")
                .body("{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}")
                .post("/api/dispatcher/jobs")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "pc-1", roles = "device")
    void device_cannot_submit() {
        given()
                .contentType("application/json")
                .body("{\"deviceIds\":[\"pc\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}")
                .post("/api/dispatcher/jobs")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "grok", roles = "dispatcher")
    void dispatcher_cannot_register() {
        given()
                .contentType("application/json")
                .body("{\"hostname\":\"pc\",\"agents\":[\"yuni\"]}")
                .post("/api/device/register")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "pc-http", roles = "device")
    void register_uses_the_caller_name_as_device_id() {
        given()
                .contentType("application/json")
                .body("{\"hostname\":\"pc-http\",\"agents\":[\"yuni\"]}")
                .post("/api/device/register")
                .then()
                .statusCode(200)
                .body("deviceId", is("pc-http"))
                .body("agents", hasItem("yuni"));
    }

    @Test
    @TestSecurity(user = "grok", roles = "dispatcher")
    void unknown_device_fails_immediately() {
        given()
                .contentType("application/json")
                .body("{\"deviceIds\":[\"missing-device\"],\"agentName\":\"yuni\",\"prompt\":\"hi\",\"channel\":\"TEXT\"}")
                .post("/api/dispatcher/jobs")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "grok", roles = "dispatcher")
    void another_dispatchers_job_is_not_found() {
        given()
                .get("/api/dispatcher/jobs/" + UUID.randomUUID())
                .then()
                .statusCode(404);
    }
}
