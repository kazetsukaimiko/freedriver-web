package io.freedriver.sms.phones;

import io.freedriver.sms.SmsConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An empty FREEDRIVER_SEED_PHONE is no seed: the service starts and adds nothing. */
@QuarkusTest
@TestProfile(EmptySeedSettingTest.EmptySeed.class)
class EmptySeedSettingTest {

    public static class EmptySeed implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("FREEDRIVER_SEED_PHONE", "");
        }
    }

    @Inject
    SmsConfig config;
    @Inject
    PhoneDirectory directory;

    @Test
    void empty_setting_starts_the_service_with_no_seed_row() {
        assertTrue(config.seedPhone().isEmpty());
        assertEquals(0, directory.list().stream().filter(e -> e.username().equals("kaze")).count());
    }
}
