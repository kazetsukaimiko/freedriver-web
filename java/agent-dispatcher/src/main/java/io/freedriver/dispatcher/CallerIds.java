package io.freedriver.dispatcher;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;

/**
 * Reads the caller off the {@link SecurityIdentity} the resource already holds.
 * {@link JobStore} receives the resulting strings.
 */
public final class CallerIds {

    private CallerIds() {
    }

    public static String subject(SecurityIdentity identity) {
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
    public static String deviceId(SecurityIdentity identity) {
        Principal principal = identity.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            Object claim = jwt.getClaim("device_id");
            if (claim instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return subject(identity);
    }
}
