package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceControl;
import io.freedriver.app.appliances.ApplianceCommandRouted;
import io.freedriver.app.appliances.CommandRateLimiter;
import io.freedriver.app.appliances.MockAutonomy;
import io.freedriver.app.security.CsrfFilter;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppliancesResourceTest {

    @Inject
    MockAutonomy mock;

    @Inject
    ApplianceControl control;

    @Inject
    CommandRateLimiter rateLimiter;

    @BeforeEach
    void reset() {
        mock.reset();
        rateLimiter.reset();
    }

    private static String commandPath(String applianceName) {
        return "/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + applianceName;
    }

    private static RequestSpecification commandSpec() {
        var get = given().when().get("/api/appliances");
        return given()
                .contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, get.path("csrfToken"));
    }

    @Test
    void get_unauthenticated_401() {
        given().when().get("/api/appliances").then().statusCode(401);
    }

    @Test
    void post_unauthenticated_401() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post(commandPath("hallway"))
                .then().statusCode(401);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void get_user_role_403() {
        given().when().get("/api/appliances").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void post_user_role_403() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post(commandPath("hallway"))
                .then().statusCode(403);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void get_returns_cabin_tab_and_six_appliances() {
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("instances.size()", is(1))
                .body("instances[0].instanceId", is(MockAutonomy.INSTANCE_ID.toString()))
                .body("instances[0].instanceName", is(MockAutonomy.INSTANCE_NAME))
                .body("instances[0].stale", is(false))
                .body("instances[0].timeout", is(false))
                .body("instances[0].lastUpdated", notNullValue())
                .body("instances[0].appliances.size()", is(6))
                .body("instances[0].appliances.applianceName", hasItems(
                        "hallway", "kitchen", "living-room", "bedroom", "garage", "porch"))
                .body("csrfToken", notNullValue())
                .cookie(CsrfFilter.COOKIE, notNullValue());
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void get_portal_admin_allowed() {
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("instances.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void get_unknown_instances_is_empty_not_409() {
        control.forget(MockAutonomy.INSTANCE_ID);
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("instances", empty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void post_without_csrf_400_no_command() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post(commandPath("hallway"))
                .then()
                .statusCode(400)
                .body(is("{}"));
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void post_wrong_csrf_400_no_command() {
        var get = given().when().get("/api/appliances");
        given().contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, get.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, "nope")
                .body("{\"on\":true}")
                .when().post(commandPath("hallway"))
                .then()
                .statusCode(400)
                .body(is("{}"));
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void extra_json_fields_400() {
        commandSpec()
                .body("{\"on\":true,\"extra\":1}")
                .when().post(commandPath("hallway"))
                .then().statusCode(400);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void missing_on_400_from_constraint() {
        commandSpec()
                .body("{}")
                .when().post(commandPath("hallway"))
                .then().statusCode(400);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void oversized_name_400_from_constraint() {
        String tooLong = "a".repeat(ApplianceSchemas.NAME_MAX + 1);
        commandSpec()
                .body("{\"on\":true}")
                .when().post(commandPath(tooLong))
                .then().statusCode(400);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void unknown_instance_404_no_command() {
        UUID missing = UUID.fromString("00000000-0000-4000-8000-000000000000");
        commandSpec()
                .body("{\"on\":true}")
                .when().post("/api/appliances/" + missing + "/hallway")
                .then().statusCode(404);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void unknown_name_404_no_command() {
        commandSpec()
                .body("{\"on\":true}")
                .when().post(commandPath("attic"))
                .then().statusCode(404);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void stale_instance_post_409_get_still_200() {
        control.markStale(MockAutonomy.INSTANCE_ID, Duration.ofSeconds(20));

        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("instances[0].stale", is(true))
                .body("instances[0].timeout", is(false))
                .body("instances[0].lastUpdated", notNullValue());

        commandSpec()
                .body("{\"on\":false}")
                .when().post(commandPath("hallway"))
                .then()
                .statusCode(409)
                .body("stale", is(true))
                .body("timeout", is(false))
                .body("instanceId", is(MockAutonomy.INSTANCE_ID.toString()));
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void confirm_path_updates_map() {
        mock.setConfirmCommands(true);

        commandSpec()
                .body("{\"on\":true}")
                .when().post(commandPath("hallway"))
                .then()
                .statusCode(200)
                .body("timeout", is(false))
                .body("stale", is(false))
                .body("instanceName", is("Cabin"))
                .body("appliances.find { it.applianceName == 'hallway' }.on", is(true))
                .body("lastUpdated", notNullValue());

        assertEquals(1, mock.publishedCommands().size());
        ApplianceCommandRouted routed = mock.publishedCommands().getFirst();
        assertEquals(MockAutonomy.INSTANCE_ID, routed.instanceId());
        assertEquals("hallway", routed.command().applianceName());
        assertTrue(routed.command().state());
        assertFalse(routed.command().commandId().isBlank());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void timeout_path_does_not_look_confirmed() {
        mock.setConfirmCommands(false);

        commandSpec()
                .body("{\"on\":true}")
                .when().post(commandPath("hallway"))
                .then()
                .statusCode(200)
                .body("timeout", is(true))
                .body("stale", is(false))
                .body("appliances.find { it.applianceName == 'hallway' }.on", is(false));

        assertEquals(1, mock.publishedCommands().size());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void rate_limit_returns_429() {
        mock.setConfirmCommands(true);
        int limited = 0;
        RequestSpecification spec = commandSpec();
        for (int i = 0; i < 12; i++) {
            int status = spec
                    .body("{\"on\":true}")
                    .when().post(commandPath("hallway"))
                    .then()
                    .extract().statusCode();
            if (status == 429) {
                limited++;
            } else {
                assertEquals(200, status);
            }
        }
        assertTrue(limited >= 1, "expected at least one 429");
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void get_is_never_rate_limited() {
        for (int i = 0; i < 12; i++) {
            given().when().get("/api/appliances").then().statusCode(200);
        }
        RequestSpecification spec = commandSpec();
        for (int i = 0; i < 12; i++) {
            spec.body("{\"on\":false}")
                    .when().post(commandPath("hallway"));
        }
        given().when().get("/api/appliances").then().statusCode(200);
    }
}
