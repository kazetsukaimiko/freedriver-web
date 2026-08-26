package io.freedriver.app.api;

import io.freedriver.app.appliances.CommandRateLimiter;
import io.freedriver.app.appliances.MockAutonomy;
import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppliancesResourceTest {

    @Inject
    MockAutonomy mock;

    @Inject
    CommandRateLimiter rateLimiter;

    @BeforeEach
    void reset() {
        mock.reset();
        rateLimiter.reset();
    }

    @Test
    void get_unauthenticated_401() {
        given().when().get("/api/appliances").then().statusCode(401);
    }

    @Test
    void post_unauthenticated_401() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/hallway")
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
                .when().post("/api/appliances/hallway")
                .then().statusCode(403);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void get_returns_six_named_appliances() {
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("stale", is(false))
                .body("timeout", is(false))
                .body("lastUpdated", notNullValue())
                .body("appliances.size()", is(6))
                .body("appliances.applianceName", hasItems(
                        "hallway", "kitchen", "living-room", "bedroom", "garage", "porch"))
                .body("appliances.find { it.applianceName == 'hallway' }.on", is(false));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void get_portal_admin_allowed() {
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("timeout", is(false))
                .body("appliances.size()", is(6));
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void get_never_received_is_stale_not_409() {
        mock.clearState();
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("stale", is(true))
                .body("timeout", is(false))
                .body("lastUpdated", nullValue())
                .body("appliances", empty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void extra_json_fields_400() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true,\"extra\":1}")
                .when().post("/api/appliances/hallway")
                .then().statusCode(400);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void missing_on_400_from_constraint() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/appliances/hallway")
                .then().statusCode(400);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void blank_path_400_from_constraint() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/{id}", " ")
                .then().statusCode(400);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void oversized_path_400_from_constraint() {
        String tooLong = "a".repeat(ApplianceSchemas.NAME_MAX + 1);
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/" + tooLong)
                .then().statusCode(400);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void post_never_received_409_no_command() {
        mock.clearState();
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/hallway")
                .then()
                .statusCode(409)
                .body("stale", is(true))
                .body("timeout", is(false))
                .body("lastUpdated", nullValue())
                .body("appliances", empty());
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void unknown_name_404_no_command() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/attic")
                .then().statusCode(404);
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void stale_map_post_409_get_still_200() {
        mock.markStale();

        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("stale", is(true))
                .body("timeout", is(false))
                .body("lastUpdated", notNullValue())
                .body("appliances.size()", is(6));

        given().contentType(ContentType.JSON)
                .body("{\"on\":false}")
                .when().post("/api/appliances/hallway")
                .then()
                .statusCode(409)
                .body("stale", is(true))
                .body("timeout", is(false));
        assertTrue(mock.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void confirm_path_updates_map() {
        mock.setConfirmCommands(true);

        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/hallway")
                .then()
                .statusCode(200)
                .body("timeout", is(false))
                .body("stale", is(false))
                .body("appliances.find { it.applianceName == 'hallway' }.on", is(true))
                .body("lastUpdated", notNullValue());

        assertEquals(1, mock.publishedCommands().size());
        ApplianceCommandMessage command = mock.publishedCommands().getFirst();
        assertEquals("hallway", command.applianceName());
        assertEquals(MockAutonomy.INSTANCE_ID, command.instanceId());
        assertTrue(command.on());
        assertEquals(command.commandId(), mock.snapshot().orElseThrow().appliedCommandId());
    }

    @Test
    @TestSecurity(user = "kaze", roles = {"dashboard"})
    void timeout_path_does_not_look_confirmed() {
        mock.setConfirmCommands(false);

        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/hallway")
                .then()
                .statusCode(200)
                .body("timeout", is(true))
                .body("stale", is(false))
                .body("appliances.find { it.applianceName == 'hallway' }.on", is(false));

        assertEquals(1, mock.publishedCommands().size());
        assertEquals(null, mock.snapshot().orElseThrow().appliedCommandId());
        assertFalse(mock.snapshot().orElseThrow().find("hallway").orElseThrow().on());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void rate_limit_returns_429() {
        mock.setConfirmCommands(true);
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            int status = given().contentType(ContentType.JSON)
                    .body("{\"on\":true}")
                    .when().post("/api/appliances/hallway")
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
        for (int i = 0; i < 12; i++) {
            given().contentType(ContentType.JSON)
                    .body("{\"on\":false}")
                    .when().post("/api/appliances/hallway");
        }
        given().when().get("/api/appliances").then().statusCode(200);
    }
}
