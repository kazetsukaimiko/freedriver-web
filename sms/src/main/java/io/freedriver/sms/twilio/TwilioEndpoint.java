package io.freedriver.sms.twilio;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The Verify API base URL. Outside the test profile it is always https://verify.twilio.com, and
 * any other configured value stops startup. The test profile may point it at a fake endpoint.
 */
public record TwilioEndpoint(URI base) {

    public static final URI PRODUCTION = URI.create("https://verify.twilio.com");

    public static TwilioEndpoint resolve(String configured, boolean testProfile) {
        URI uri = parse(configured);
        if (!testProfile) {
            if (!isProduction(uri)) {
                throw new IllegalStateException(
                        "Twilio base URL must be " + PRODUCTION + " outside the test profile");
            }
            return new TwilioEndpoint(PRODUCTION);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw new IllegalStateException("Test Twilio base URL must be a bare http(s) origin");
        }
        return new TwilioEndpoint(URI.create(scheme + "://" + uri.getRawAuthority()));
    }

    private static boolean isProduction(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "verify.twilio.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"));
    }

    private static URI parse(String configured) {
        try {
            return new URI(configured == null ? "" : configured.strip());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Twilio base URL is not a valid URL");
        }
    }

    public URI verifications(String serviceSid) {
        return URI.create(base + "/v2/Services/" + serviceSid + "/Verifications");
    }

    public URI verificationCheck(String serviceSid) {
        return URI.create(base + "/v2/Services/" + serviceSid + "/VerificationCheck");
    }
}
