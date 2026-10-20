package io.freedriver.dispatcher;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;

@ApplicationScoped
public class CallerIdentity {

    private final SecurityIdentity identity;

    @Inject
    public CallerIdentity(SecurityIdentity identity) {
        this.identity = identity;
    }

    public String subject() {
        Principal principal = identity.getPrincipal();
        if (principal == null || identity.isAnonymous() || principal.getName() == null || principal.getName().isBlank()) {
            throw new DispatcherException(DispatcherException.Kind.FORBIDDEN, "missing caller");
        }
        return principal.getName();
    }

    /**
     * Device id is the {@code device_id} claim when the token has one (the provisioned
     * client id). Tests and tokens without that claim use the principal name.
     */
    public String deviceId() {
        Principal principal = identity.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            Object claim = jwt.getClaim("device_id");
            if (claim instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return subject();
    }
}
