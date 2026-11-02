package io.freedriver.autonomymcp;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CallerIds {

    private CallerIds() {
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
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        return null;
    }

    public static String azp(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        Object attribute = identity.getAttribute("azp");
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        Principal principal = identity.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            Object claim = jwt.getClaim("azp");
            if (claim instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static Set<String> roles(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(identity.getRoles()));
    }
}
