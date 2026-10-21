package io.freedriver.sms.client;

import java.net.URI;
import java.time.Duration;

/**
 * Where the client calls, and the shared secret. The secret is not a parameter of {@code SmsApi},
 * so it cannot appear on the interface or in a proxy's {@code toString}.
 */
public final class SmsClientConfig {

    /** sms on the compose network. The only production host that receives the secret. */
    public static final URI BASE_URL = URI.create("http://sms:8080");

    /** Same header the sms service requires. */
    public static final String HEADER = "X-Freedriver-Sms-Secret";

    /** Same placeholder the service treats as unset. */
    public static final String PLACEHOLDER = "placeholder-not-a-live-secret";

    /** Per-request timeout. Matches the #174 client. */
    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** TCP connect timeout. Matches the #174 client. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final URI baseUrl;
    private final String sharedSecret;

    public SmsClientConfig(URI baseUrl, String sharedSecret) {
        this.baseUrl = baseUrl;
        this.sharedSecret = sharedSecret;
    }

    /** Production client: the fixed base URL and a secret from configuration. */
    public static SmsClientConfig atFixedBase(String sharedSecret) {
        return new SmsClientConfig(BASE_URL, sharedSecret);
    }

    URI baseUrl() {
        return baseUrl;
    }

    String sharedSecret() {
        return sharedSecret;
    }

    @Override
    public String toString() {
        return "SmsClientConfig[baseUrl=" + baseUrl + "]";
    }
}
