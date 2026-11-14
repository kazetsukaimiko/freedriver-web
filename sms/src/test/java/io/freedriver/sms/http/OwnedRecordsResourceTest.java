package io.freedriver.sms.http;

import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.phones.SignInTimes;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.freedriver.sms.support.SmsCalls.SECRET;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

/** sms owns the phones and sign-ins rows and serves them, typed, to callers with the shared secret. */
@QuarkusTest
class OwnedRecordsResourceTest {

    @Inject
    PhoneDirectory directory;

    @Inject
    SignInTimes signIns;

    @Test
    void phone_records_are_typed_rows() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Ada", "ada.user", "dashboard", Instant.parse("2026-10-01T00:00:00Z")));
        try {
            given().header("X-Freedriver-Sms-Secret", SECRET).when().get("/sms/phones/records")
                    .then().statusCode(200)
                    .body("phones.id", hasItem(phone))
                    .body("phones.findAll { it.phone == '" + phone + "' }.name", hasItem("Ada"))
                    .body("phones.findAll { it.phone == '" + phone + "' }.username", hasItem("ada.user"))
                    .body("phones.findAll { it.phone == '" + phone + "' }.addedAt", hasItem("2026-10-01T00:00:00Z"));
        } finally {
            directory.remove(phone);
        }
    }

    @Test
    void sign_in_records_are_typed_rows() {
        String phone = TestNumbers.fresh();
        signIns.record(phone);
        try {
            given().header("X-Freedriver-Sms-Secret", SECRET).when().get("/sms/sign-ins")
                    .then().statusCode(200)
                    .body("signIns.phone", hasItem(phone));
        } finally {
            signIns.forget(phone);
        }
    }

    @Test
    void the_records_need_the_shared_secret() {
        given().when().get("/sms/phones/records").then().statusCode(401);
        given().when().get("/sms/sign-ins").then().statusCode(401);
    }
}
