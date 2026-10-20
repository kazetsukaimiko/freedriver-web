package io.freedriver.sms.api;

import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.freedriver.sms.support.SmsCalls.SECRET;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class PhoneListResourceTest {

    @Inject
    PhoneDirectory directory;

    @Test
    void remove_drops_the_number_and_does_not_require_keycloak() {
        String phone = "+15555550999";
        directory.remove(phone);
        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.parse("2026-10-01T00:00:00Z")));

        given().header("X-Freedriver-Sms-Secret", SECRET).when().get("/sms/phones")
                .then().statusCode(200).body("phones.size()", is(1)).body("phones[0].name", is("Mom"));

        given().header("X-Freedriver-Sms-Secret", SECRET).contentType("application/json")
                .body("{\"phone\":\"" + phone + "\"}").post("/sms/phones/remove")
                .then().statusCode(200).body("removed", is(true));

        assertFalse(directory.find(phone).isPresent());
        given().header("X-Freedriver-Sms-Secret", SECRET).contentType("application/json")
                .body("{\"phone\":\"" + phone + "\"}").post("/sms/phones/remove")
                .then().statusCode(200).body("removed", is(false));
    }
}
