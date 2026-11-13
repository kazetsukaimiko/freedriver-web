package io.freedriver.sms.twilio;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** HTTP basic auth with the API key SID and secret. {@link #toString()} prints neither. */
final class TwilioBasicAuth implements ClientRequestFilter {

    private final String authorization;

    TwilioBasicAuth(TwilioCredentials credentials) {
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (credentials.apiKeySid() + ":" + credentials.apiKeySecret()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void filter(ClientRequestContext request) {
        request.getHeaders().putSingle("Authorization", authorization);
    }

    @Override
    public String toString() {
        return "TwilioBasicAuth[redacted]";
    }
}
