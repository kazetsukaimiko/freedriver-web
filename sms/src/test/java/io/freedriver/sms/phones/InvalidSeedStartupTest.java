package io.freedriver.sms.phones;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A FREEDRIVER_SEED_PHONE that is not a +1 number stops startup instead of being stored. */
@QuarkusMainTest
@TestProfile(InvalidSeedStartupTest.NineDigitSeed.class)
class InvalidSeedStartupTest {

    static final String INVALID = "+1555555816";

    public static class NineDigitSeed implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("FREEDRIVER_SEED_PHONE", INVALID);
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void invalid_seed_refuses_startup_without_logging_the_value(LaunchResult result) {
        String output = result.getOutput() + "\n" + result.getErrorOutput();
        assertTrue(output.contains("freedriver.sms.seed-phone"), output);
        assertFalse(output.contains("555555816"), "seed value must not be logged: " + output);
        assertFalse(output.contains("Seed row"), output);
    }
}
