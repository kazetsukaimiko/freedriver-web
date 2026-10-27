package io.freedriver.app.instances;

import io.freedriver.app.security.CsrfFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class InstancesResourceTest {

    @Inject
    RecordingInstanceIdentity identity;

    @BeforeEach
    void reset() {
        identity.clear();
    }

    @Test
    @TestSecurity(user = "house-admin", roles = {"portal-admin"})
    void create_returns_the_username_and_the_password_just_set() {
        Response created = csrf().body("{\"label\":\"  porch \"}").post("/api/instances");
        created.then().statusCode(201).body("label", is("porch"));
        String instanceId = created.path("instanceId");
        String password = created.path("password");
        assertEquals(instanceId, UUID.fromString(instanceId).toString());
        assertTrue(password.matches("[A-Za-z0-9_-]{43}"));
        assertFalse(created.asString().contains("brokerSecret"));
        assertFalse(created.asString().contains("keycloakUserId"));
        RecordingInstanceIdentity.Stored stored = identity.user(instanceId);
        assertEquals(password, stored.password());
        assertEquals("mqtt-instance", stored.group());
        assertEquals("freedriver/v1/" + instanceId + "/appliances", stored.publishTopic());
        assertEquals("freedriver/v1/" + instanceId + "/commands", stored.subscribeTopic());

        Response second = csrf().body("{}").post("/api/instances");
        second.then().statusCode(201);
        String other = second.path("instanceId");
        assertNull(second.path("label"));
        assertFalse(identity.user(other).publishTopic().contains(instanceId));
        assertFalse(identity.user(other).subscribeTopic().contains(instanceId));
        assertNotEquals(stored.publishTopic(), identity.user(other).publishTopic());

        String list = given().when().get("/api/instances").asString();
        assertFalse(list.contains(password));
        assertFalse(list.contains(second.path("password").toString()));
        assertFalse(list.contains("\"password\""));
        given().when().get("/api/instances").then().statusCode(200).body("instances.size()", is(2));
    }

    @Test
    @TestSecurity(user = "house-admin", roles = {"portal-admin"})
    void a_refused_create_leaves_mqtt_instance_without_that_user() {
        identity.refuse(true);
        Response refused = csrf().body("{\"label\":\"porch\"}").post("/api/instances");
        refused.then().statusCode(502).body("type", is("instance")).body("cause", is("broker"));
        assertFalse(refused.asString().contains("instanceId"));
        assertFalse(refused.asString().contains("password"));
        assertTrue(identity.empty());
        assertEquals(1, identity.creates());
        given().when().get("/api/instances").then().body("instances.size()", is(0));
    }

    @Test
    @TestSecurity(user = "house-admin", roles = {"portal-admin"})
    void a_long_label_is_refused_before_keycloak() {
        String tooLong = "n".repeat(65);
        Response refused = csrf().body("{\"label\":\"" + tooLong + "\"}").post("/api/instances");
        refused.then().statusCode(400).body("type", is("instance")).body("cause", is("label"));
        assertFalse(refused.asString().contains("instanceId"));
        assertEquals(0, identity.creates());
        assertTrue(identity.empty());
    }

    @Test
    @TestSecurity(user = "house-admin", roles = {"portal-admin"})
    void open_returns_a_new_password_and_not_the_previous_one() {
        Response created = csrf().body("{\"label\":\"porch\"}").post("/api/instances");
        String instanceId = created.path("instanceId");
        String first = created.path("password");
        Response opened = csrf().post("/api/instances/" + instanceId + "/open");
        opened.then().statusCode(200).body("instanceId", is(instanceId)).body("label", is("porch"));
        String second = opened.path("password");
        assertNotEquals(first, second);
        assertEquals(second, identity.user(instanceId).password());
        assertFalse(given().when().get("/api/instances").asString().contains(second));

        String missing = "00000000-0000-4000-8000-000000000099";
        csrf().post("/api/instances/" + missing + "/open").then()
                .statusCode(404)
                .body("type", is("instance"))
                .body("cause", is("missing"))
                .body("instanceId", is(missing));
        csrf().post("/api/instances/" + instanceId.toUpperCase() + "/open").then()
                .statusCode(404)
                .body("cause", is("missing"));
    }

    @Test
    @TestSecurity(user = "house-admin", roles = {"portal-admin"})
    void create_without_csrf_is_refused() {
        given().contentType(ContentType.JSON).body("{}").post("/api/instances").then().statusCode(400);
        assertEquals(0, identity.creates());
    }

    @Test
    @TestSecurity(user = "ada", roles = {"dashboard"})
    void another_role_is_forbidden() {
        given().when().get("/api/instances").then().statusCode(403);
        given().contentType(ContentType.JSON).body("{}").post("/api/instances").then().statusCode(403);
        assertEquals(0, identity.creates());
    }

    @Test
    @TestSecurity(user = "house-admin", roles = {"portal-admin"})
    void list_carries_the_csrf_token_and_no_password() {
        given().when().get("/api/instances").then()
                .statusCode(200)
                .cookie(CsrfFilter.COOKIE)
                .body("csrfToken", not(is("")))
                .body("instances.size()", is(0));
    }

    private static io.restassured.specification.RequestSpecification csrf() {
        Response get = given().when().get("/api/instances");
        get.then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, get.path("csrfToken"));
    }
}
