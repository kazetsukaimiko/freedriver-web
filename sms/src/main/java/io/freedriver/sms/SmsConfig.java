package io.freedriver.sms;

import io.freedriver.sms.validation.UsPhoneNumber;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Settings of the sms service under {@code freedriver.sms}. */
@ConfigMapping(prefix = "freedriver.sms")
public interface SmsConfig {

    /** Which sender delivers codes: {@code twilio} (default), or {@code fake} in dev and test builds. */
    @WithDefault("twilio")
    String sender();

    /** Shared secret Keycloak sends in {@code X-Freedriver-Sms-Secret}. Empty or the placeholder turns the OTP endpoints off. */
    Optional<String> sharedSecret();

    /**
     * kaze's number from the server setting {@code FREEDRIVER_SEED_PHONE}. Startup puts it on the
     * phone list when it is not there yet. It passes the same +1 check as every request number, and
     * a value that fails it stops startup.
     */
    Optional<@Pattern(regexp = UsPhoneNumber.REGEX, message = UsPhoneNumber.MESSAGE) String> seedPhone();

    /** Directory holding the phone list and the agreement records. */
    Path dataDir();

    /** Sends allowed across all numbers per UTC day. Set in server config; there is no production default. */
    @Min(1)
    int dailySendCap();

    RateLimit rateLimit();

    Twilio twilio();

    interface RateLimit {

        @WithDefault("15m")
        Duration window();

        @Min(1)
        @WithDefault("5")
        int sendsPerNumber();

        @Min(1)
        @WithDefault("5")
        int wrongCodesPerNumber();
    }

    interface Twilio {

        /** Verify API base URL. Only the test profile may point it anywhere but https://verify.twilio.com. */
        @WithDefault(TwilioDefaults.BASE_URL)
        String baseUrl();

        /** Connect and request timeout for each Verify call. */
        @WithDefault("5s")
        Duration timeout();
    }

    final class TwilioDefaults {
        public static final String BASE_URL = "https://verify.twilio.com";

        private TwilioDefaults() {
        }
    }
}
