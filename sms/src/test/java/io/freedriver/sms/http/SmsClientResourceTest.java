package io.freedriver.sms.http;

import io.freedriver.sms.client.SmsClient;
import io.freedriver.sms.model.OtpSendRequest;
import io.freedriver.sms.model.SendType;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyRequest;
import io.freedriver.sms.client.SmsClientConfig;
import io.freedriver.sms.client.SmsClientException;
import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SmsClient} against the running sms resource, same paths and answers as the HTTP contract. */
@QuarkusTest
class SmsClientResourceTest {

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;
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
    void client_sends_and_verifies_against_the_resource() {
        assertNotEquals(8080, RestAssured.port);
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        SmsClient client = client(SmsCalls.SECRET);

        SentResponse sent = client.send(new OtpSendRequest(phone, null));
        assertEquals(SendType.OTP, sent.sent().type());
        assertEquals(phone, sent.sent().phone());
        String code = sender.pendingCode(phone).orElseThrow();
        VerifiedResponse verified = client.verify(new VerifyRequest(phone, code));
        assertEquals(phone, verified.verified().phone());
        assertEquals(SendType.OTP, verified.verified().type());
        assertFalse(client.toString().contains(SmsCalls.SECRET));
        assertTrue(java.lang.reflect.Proxy.isProxyClass(client.getClass()));
    }

    @Test
    void wrong_secret_and_wrong_code_do_not_echo_the_request() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Mom", "house.user", "dashboard", Instant.now()));
        consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        String wrongSecret = "wrong-secret-value";
        SmsClientException unauthorized = assertThrows(SmsClientException.class,
                () -> client(wrongSecret).send(new OtpSendRequest(phone, null)));
        assertEquals(401, unauthorized.status());
        assertEquals("unauthorized", unauthorized.error());
        assertFalse(unauthorized.getMessage().contains(wrongSecret));
        assertFalse(unauthorized.getMessage().contains(phone));
        assertEquals(0, sender.deliveries());

        SmsClient client = client(SmsCalls.SECRET);
        client.send(new OtpSendRequest(phone, null));
        SmsClientException rejected = assertThrows(SmsClientException.class,
                () -> client.verify(new VerifyRequest(phone, "000000")));
        assertEquals(400, rejected.status());
        assertEquals("invalid-code", rejected.error());
        assertFalse(rejected.getMessage().contains(phone));
        assertFalse(rejected.getMessage().contains("000000"));
        assertFalse(rejected.getMessage().contains("{"));
    }

    private static SmsClient client(String secret) {
        return SmsClient.create(new SmsClientConfig(URI.create("http://127.0.0.1:" + RestAssured.port), secret));
    }
}
