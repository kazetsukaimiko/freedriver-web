package io.freedriver.sms.security;

import io.freedriver.sms.SmsConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * App-wide caller check: every REST request must carry Keycloak's shared secret in
 * {@code X-Freedriver-Sms-Secret}. Fail-closed: an unset, blank or placeholder secret refuses
 * every request with {@link UnauthorizedCallerException}. Health endpoints are not REST resources and stay open for the container check.
 */
@Provider
@ApplicationScoped
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class SharedSecretFilter implements ContainerRequestFilter {

    public static final String HEADER = "X-Freedriver-Sms-Secret";
    static final String PLACEHOLDER = "placeholder-not-a-live-secret";

    private final byte[] secret;

    @Inject
    public SharedSecretFilter(SmsConfig config) {
        this.secret = usable(config.sharedSecret()).map(s -> s.getBytes(StandardCharsets.UTF_8)).orElse(null);
    }

    static Optional<String> usable(Optional<String> raw) {
        return raw.map(String::strip).filter(s -> !s.isEmpty() && !s.equals(PLACEHOLDER));
    }

    @Override
    public void filter(ContainerRequestContext request) {
        String header = request.getHeaderString(HEADER);
        if (secret == null || header == null
                || !MessageDigest.isEqual(secret, header.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedCallerException();
        }
    }
}
