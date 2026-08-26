package io.freedriver.app.api;

import io.freedriver.app.appliances.FakeApplianceBackend;
import io.freedriver.autonomy.mqtt.contract.Appliance;
import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;
import io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppliancesResourceTest {

    @Inject
    FakeApplianceBackend fake;

    @BeforeEach
    void reset() {
        fake.reset();
    }

    @Test
    void get_unauthenticated_401() {
        given().when().get("/api/appliances").then().statusCode(401);
    }

    @Test
    void post_unauthenticated_401() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/living-room-lamp")
                .then().statusCode(401);
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void get_user_role_403() {
        given().when().get("/api/appliances").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void post_user_role_403() {
        seedFreshLamp(false);
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/living-room-lamp")
                .then().statusCode(403);
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void get_dashboard_allowed() {
        seedFreshLamp(true);
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("stale", is(false))
                .body("timeout", is(false))
                .body("lastUpdated", notNullValue())
                .body("appliances[0].applianceName", is("living-room-lamp"))
                .body("appliances[0].on", is(true));
    }

    @Test
    @TestSecurity(user = "yuni", roles = {"portal-admin"})
    void get_portal_admin_allowed() {
        seedFreshLamp(true);
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("timeout", is(false))
                .body("appliances.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void get_never_received_is_stale_not_409() {
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("stale", is(true))
                .body("timeout", is(false))
                .body("lastUpdated", nullValue())
                .body("appliances", empty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void extra_json_fields_400() {
        seedFreshLamp(false);
        given().contentType(ContentType.JSON)
                .body("{\"on\":true,\"extra\":1}")
                .when().post("/api/appliances/living-room-lamp")
                .then().statusCode(400);
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void missing_on_400_from_constraint() {
        seedFreshLamp(false);
        given().contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/appliances/living-room-lamp")
                .then().statusCode(400);
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void blank_path_400_from_constraint() {
        seedFreshLamp(false);
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/{id}", " ")
                .then().statusCode(400);
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void oversized_path_400_from_constraint() {
        seedFreshLamp(false);
        String tooLong = "a".repeat(ApplianceSchemas.NAME_MAX + 1);
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/" + tooLong)
                .then().statusCode(400);
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void post_never_received_409_no_command() {
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/living-room-lamp")
                .then()
                .statusCode(409)
                .body("stale", is(true))
                .body("timeout", is(false))
                .body("lastUpdated", nullValue())
                .body("appliances", empty());
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void unknown_name_404_no_command() {
        seedFreshLamp(false);
        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/kitchen-toaster")
                .then().statusCode(404);
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void stale_map_post_409_get_still_200() {
        seedFreshLamp(true);
        fake.markStale();

        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .body("stale", is(true))
                .body("timeout", is(false))
                .body("lastUpdated", notNullValue())
                .body("appliances[0].on", is(true));

        given().contentType(ContentType.JSON)
                .body("{\"on\":false}")
                .when().post("/api/appliances/living-room-lamp")
                .then()
                .statusCode(409)
                .body("stale", is(true))
                .body("timeout", is(false));
        assertTrue(fake.publishedCommands().isEmpty());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void confirm_path_updates_map() {
        seedFreshLamp(false);
        fake.setConfirmCommands(true);

        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/living-room-lamp")
                .then()
                .statusCode(200)
                .body("timeout", is(false))
                .body("stale", is(false))
                .body("appliances[0].on", is(true))
                .body("lastUpdated", notNullValue());

        assertEquals(1, fake.publishedCommands().size());
        ApplianceCommandMessage command = fake.publishedCommands().getFirst();
        assertEquals("living-room-lamp", command.applianceName());
        assertEquals(FakeApplianceBackend.INSTANCE_ID, command.instanceId());
        assertTrue(command.on());
        assertEquals(command.commandId(), fake.snapshot().orElseThrow().appliedCommandId());
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void timeout_path_does_not_look_confirmed() {
        seedFreshLamp(false);
        fake.setConfirmCommands(false);

        given().contentType(ContentType.JSON)
                .body("{\"on\":true}")
                .when().post("/api/appliances/living-room-lamp")
                .then()
                .statusCode(200)
                .body("timeout", is(true))
                .body("stale", is(false))
                .body("appliances[0].on", is(false));

        assertEquals(1, fake.publishedCommands().size());
        assertEquals(null, fake.snapshot().orElseThrow().appliedCommandId());
        assertEquals(false, fake.snapshot().orElseThrow().find("living-room-lamp").orElseThrow().on());
    }

    private void seedFreshLamp(boolean on) {
        fake.publishState(new ApplianceStateMessage(
                FakeApplianceBackend.INSTANCE_ID,
                FakeApplianceBackend.INSTANCE_NAME,
                null,
                List.of(new Appliance("living-room-lamp", on))));
    }
}
