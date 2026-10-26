package io.freedriver.sms.http;

import io.freedriver.sms.invite.FakeInvitationSender;
import io.freedriver.sms.invite.InvitationLink;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.security.SharedSecretFilter;
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class InvitationTextResourceTest {

    @Inject
    FakeInvitationSender invitations;
    @Inject
    ConsentLedger consents;
    @Inject
    PhoneDirectory directory;
    @Inject
    PhoneRateLimiter limiter;
    @Inject
    DailySendCap dailyCap;

    @BeforeEach
    void reset() {
        invitations.reset();
        limiter.reset();
        dailyCap.reset();
    }

    @Test
    void one_create_sends_one_invitation_text_and_writes_no_agreement() {
        String phone = TestNumbers.fresh();
        try (LogCapture logs = LogCapture.open()) {
            given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).contentType("application/json")
                    .body(Map.of("phone", phone))
                    .post("/sms/invite/text")
                    .then().statusCode(200)
                    .body("sent.type", equalTo("invite"))
                    .body("sent.phone", equalTo(phone));
            assertFalse(logs.all().contains(phone), logs.all());
        }

        assertEquals(1, invitations.sent().size());
        String text = invitations.sent().getFirst().body();
        assertEquals(InvitationLink.text("https://app.freedriver.io", phone), text);
        URI link = URI.create(text.substring(text.indexOf("https://")));
        assertEquals(phone, link.getRawFragment());
        assertEquals("/login", link.getPath());
        assertNull(link.getRawQuery());
        assertTrue(consents.history(phone).isEmpty());
        assertTrue(directory.find(phone).isEmpty());

        given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).contentType("application/json")
                .body(Map.of("type", "invite", "phone", phone))
                .post("/sms/send")
                .then().statusCode(400);
        assertEquals(1, invitations.sent().size());
    }

    @Test
    void a_bad_number_counts_toward_no_limit() {
        given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).contentType("application/json")
                .body(Map.of("phone", "555"))
                .post("/sms/invite/text")
                .then().statusCode(400).body("error", equalTo("bad-request"));
        assertTrue(invitations.sent().isEmpty());
    }

    @Test
    void the_sixth_text_to_one_number_is_rate_limited_and_writes_no_agreement() {
        String phone = TestNumbers.fresh();
        for (int i = 0; i < 5; i++) {
            given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).contentType("application/json")
                    .body(Map.of("phone", phone))
                    .post("/sms/invite/text")
                    .then().statusCode(200);
        }
        given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET).contentType("application/json")
                .body(Map.of("phone", phone))
                .post("/sms/invite/text")
                .then().statusCode(429).body("error", equalTo("rate-limited"));
        assertEquals(5, invitations.sent().size());
        assertTrue(consents.history(phone).isEmpty());
    }
}
