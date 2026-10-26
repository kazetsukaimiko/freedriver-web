package io.freedriver.sms.http;

import io.freedriver.sms.invite.FakeInvitationSender;
import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.security.SharedSecretFilter;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Invitation texts share the daily cap with codes that reach the provider. */
@QuarkusTest
@TestProfile(InvitationTextCapTest.SmallCap.class)
class InvitationTextCapTest {

    static final int CAP = 2;

    public static class SmallCap implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("freedriver.sms.daily-send-cap", String.valueOf(CAP));
        }
    }

    @Inject
    FakeInvitationSender invitations;
    @Inject
    FakeSmsSender sender;
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
        sender.reset();
        limiter.reset();
        dailyCap.reset();
    }

    private static void text(String phone, int status) {
        io.restassured.RestAssured.given().header(SharedSecretFilter.HEADER, SmsCalls.SECRET)
                .contentType("application/json")
                .body(Map.of("phone", phone))
                .post("/sms/invite/text")
                .then().statusCode(status);
    }

    @Test
    void an_unconfigured_sender_leaves_the_cap_for_a_later_text() {
        String phone = TestNumbers.fresh();
        invitations.configured(false);
        text(phone, 503);
        assertTrue(invitations.sent().isEmpty());
        assertTrue(consents.history(phone).isEmpty());

        invitations.configured(true);
        text(phone, 200);
        text(TestNumbers.fresh(), 200);
        text(TestNumbers.fresh(), 503);
        assertEquals(CAP, invitations.sent().size());
        assertTrue(consents.history(phone).isEmpty());
    }

    @Test
    void invitation_texts_and_agreed_codes_share_the_daily_cap() {
        String listed = TestNumbers.fresh();
        directory.add(new PhoneEntry(listed, "Guest", "guest.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(listed, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        SmsCalls.send(listed).statusCode(200).body("sent.type", equalTo("otp"));

        String invited = TestNumbers.fresh();
        text(invited, 200);
        text(TestNumbers.fresh(), 503);
        assertEquals(1, invitations.sent().size());
        assertEquals(1, sender.deliveries());
        assertTrue(consents.history(invited).isEmpty());
        directory.remove(listed);
    }
}
