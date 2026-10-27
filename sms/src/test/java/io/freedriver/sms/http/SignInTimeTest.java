package io.freedriver.sms.http;

import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.SignInTimes;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.security.SharedSecretFilter;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.freedriver.sms.support.SmsCalls.send;
import static io.freedriver.sms.support.SmsCalls.verify;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whitelist does not record a sign-in or an agreement. The accepted code does.
 */
@QuarkusTest
class SignInTimeTest {

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;
    @Inject
    SignInTimes signIns;
    @Inject
    FakeSmsSender sender;
    @Inject
    PhoneRateLimiter limiter;
    @Inject
    DailySendCap dailyCap;

    @BeforeEach
    void reset() {
        sender.reset();
        limiter.reset();
        dailyCap.reset();
    }

    @Test
    void whitelist_does_not_record_a_sign_in_and_an_accepted_code_does() {
        String phone = TestNumbers.fresh();
        directory.remove(phone);
        signIns.forget(phone);

        given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).contentType("application/json")
                .body("{\"phone\":\"" + phone + "\"}")
                .post("/sms/phones/whitelist")
                .then().statusCode(200).body("added", is(true));
        assertTrue(consents.history(phone).isEmpty());
        assertNull(signIns.last(phone));
        listed(phone).body("lastSignIn", nullValue());

        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.SIGN_IN_FORM, Instant.parse("2026-10-26T00:00:00Z")));
        send(phone).statusCode(200);
        String code = sender.pendingCode(phone).orElseThrow();
        String wrong = "000000".equals(code) ? "111111" : "000000";
        verify(phone, wrong).statusCode(400);
        assertNull(signIns.last(phone));

        verify(phone, code).statusCode(200);
        assertEquals(1, consents.history(phone).size());
        listed(phone).body("lastSignIn", notNullValue());

        given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).contentType("application/json")
                .body("{\"phone\":\"" + phone + "\"}")
                .post("/sms/phones/remove")
                .then().statusCode(200).body("removed", is(true));
        assertNull(signIns.last(phone));
    }

    private static io.restassured.response.ValidatableResponse listed(String phone) {
        return given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).when().get("/sms/phones")
                .then().statusCode(200)
                .rootPath("phones.find { it.phone == '" + phone + "' }");
    }
}
