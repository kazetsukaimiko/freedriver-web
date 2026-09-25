package io.freedriver.keycloak.sms;

import java.net.URI;
import java.util.Optional;

/**
 * Shared secret and sms URL for the Keycloak side.
 * The secret comes from the SMS_OTP_SHARED_SECRET environment variable.
 */
public final class SmsOtpConfig {

    public static final String SECRET_ENV = "SMS_OTP_SHARED_SECRET";
    public static final String PLACEHOLDER = "placeholder-not-a-live-secret";
    public static final String HEADER = "X-Freedriver-Sms-Secret";

    /** sms on the compose network, the one host that receives the secret. */
    public static final URI BASE_URL = URI.create("http://sms:8080");

    private SmsOtpConfig() {}

    public static Optional<String> secret() {
        return normalize(System.getenv(SECRET_ENV));
    }

    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || PLACEHOLDER.equals(trimmed)) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }
}
