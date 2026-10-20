package io.freedriver.app.security;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;

/**
 * OIDC {@code sub} on an identity: the {@code sub} attribute, otherwise
 * {@link JsonWebToken#getSubject()}. Absent and blank are null. This is not the principal name.
 */
public final class OidcSubjects {

    private OidcSubjects() {
    }

    public static String subject(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        Object attribute = identity.getAttribute("sub");
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        Principal principal = identity.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        return null;
    }
}
