package io.freedriver.sms.api;

import io.freedriver.sms.SmsConfig;
import io.freedriver.sms.otp.FakeSmsSender;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.support.LogCapture;
import io.freedriver.sms.support.SameAnswer;
import io.freedriver.sms.support.SmsCalls;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seeded number starts with no agreement. Its first sign-in sends the wording shown under the
 * number field, and sms stores the agreement before it sends the code. The tests share one seeded
 * number, so they run in order: every refused case first, then the first sign-in that stores it.
 */
@QuarkusTest
@TestProfile(SeedFirstSignInTest.Seeded.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedFirstSignInTest {

    static final String SEED = "+15555558168";

    public static class Seeded implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("FREEDRIVER_SEED_PHONE", SEED);
        }
    }

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;
    @Inject
    SmsConfig config;
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
    @Order(1)
    void startup_seeded_the_number_as_kaze_with_dashboard_and_no_agreement() {
        PhoneEntry row = directory.find(SEED).orElseThrow();
        assertEquals("kaze", row.username());
        assertEquals("dashboard", row.role());
        assertEquals(List.of(), consents.history(SEED));
    }

    @Test
    @Order(2)
    void without_the_agreement_the_seeded_number_gets_no_code() {
        SmsCalls.send(SEED).statusCode(200).body("sent.phone", equalTo(SEED));

        assertEquals(0, sender.deliveries());
        assertEquals(List.of(), consents.history(SEED));
    }

    @Test
    @Order(3)
    void other_wording_is_a_bad_request_and_stores_nothing() {
        SmsCalls.sendAgreeing(SEED, "I agree.").statusCode(400).body("error", equalTo("bad-request"));

        assertEquals(0, sender.deliveries());
        assertEquals(List.of(), consents.history(SEED));
    }

    @Test
    @Order(4)
    void no_code_is_sent_when_storing_the_agreement_fails() throws IOException {
        var withoutAgreement = SmsCalls.send(SEED).extract();
        // A directory where the agreement log should be makes the append fail.
        Path log = config.dataDir().resolve("consents.jsonl");
        Path aside = config.dataDir().resolve("consents.jsonl.aside");
        boolean existed = Files.exists(log);
        if (existed) {
            Files.move(log, aside, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(log);

        try (LogCapture logs = LogCapture.open()) {
            var failed = SmsCalls.sendAgreeing(SEED, TestNumbers.WORDING).extract();

            assertEquals("{\"sent\":{\"type\":\"otp\",\"phone\":\"" + SEED + "\"}}", failed.asString());
            SameAnswer.assertSameAnswer(withoutAgreement, failed);
            assertTrue(logs.all().contains("Sign-in agreement for ***68 not stored, so no code was sent"), logs.all());
            assertFalse(logs.all().contains("168"), logs.all());
        } finally {
            Files.delete(log);
            if (existed) {
                Files.move(aside, log);
            }
        }
        assertEquals(0, sender.deliveries());
        assertEquals(List.of(), consents.history(SEED));
    }

    @Test
    @Order(5)
    void past_the_daily_cap_the_first_sign_in_stores_nothing_and_sends_no_code() {
        while (dailyCap.tryAcquire()) {
            // use up today's cap
        }

        SmsCalls.sendAgreeing(SEED, TestNumbers.WORDING).statusCode(200).body("sent.phone", equalTo(SEED));

        assertEquals(0, sender.deliveries());
        assertEquals(List.of(), consents.history(SEED));
    }

    @Test
    @Order(6)
    void the_agreement_is_stored_only_for_the_seeded_number() {
        String listed = TestNumbers.fresh();
        directory.add(new PhoneEntry(listed, "Guest", "guest.user", "dashboard", Instant.now()));
        String unlisted = TestNumbers.fresh();

        SmsCalls.sendAgreeing(listed, TestNumbers.WORDING).statusCode(200);
        SmsCalls.sendAgreeing(unlisted, TestNumbers.WORDING).statusCode(200);

        assertEquals(0, sender.deliveries());
        assertEquals(List.of(), consents.history(listed));
        assertEquals(List.of(), consents.history(unlisted));
    }

    @Test
    @Order(7)
    void first_sign_in_stores_the_agreement_before_the_code_is_sent() {
        Instant before = Instant.now();

        SmsCalls.sendAgreeing(SEED, TestNumbers.WORDING).statusCode(200)
                .body("sent.type", equalTo("otp")).body("sent.phone", equalTo(SEED));

        List<ConsentRecord> history = consents.history(SEED);
        assertEquals(1, history.size());
        ConsentRecord stored = history.getFirst();
        assertEquals(ConsentPurpose.SIGN_IN_CODES, stored.purpose());
        assertEquals(TestNumbers.WORDING, stored.wording());
        assertEquals(ConsentSource.SEEDED_FIRST_SIGN_IN, stored.source());
        assertFalse(stored.givenAt().isBefore(before));
        // The sender refuses a number with no agreement on record, so a delivered code means the
        // agreement was stored first.
        assertEquals(1, sender.deliveries());
        assertTrue(sender.pendingCode(SEED).isPresent());
    }

    @Test
    @Order(8)
    void later_sign_ins_send_a_code_without_storing_another_agreement() {
        SmsCalls.sendAgreeing(SEED, TestNumbers.WORDING).statusCode(200);
        SmsCalls.send(SEED).statusCode(200);

        assertEquals(2, sender.deliveries());
        assertEquals(1, consents.history(SEED).size());
    }
}
