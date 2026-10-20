package io.freedriver.app.security;

import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RateLimitedFilterTest {

    @Test
    void callerKey_prefers_oidc_sub_attribute() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("scott"))
                .addAttribute("sub", "oidc-sub-1")
                .addRole("dashboard")
                .build();
        assertEquals("oidc-sub-1", RateLimitedFilter.callerKey(identity));
    }

    @Test
    void callerKey_uses_jwt_subject_when_attribute_absent() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new TokenPrincipal("scott", "jwt-sub-9"))
                .addRole("dashboard")
                .build();
        assertEquals("jwt-sub-9", RateLimitedFilter.callerKey(identity));
    }

    @Test
    void callerKey_falls_back_to_principal_name() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("scott"))
                .addRole("dashboard")
                .build();
        assertEquals("scott", RateLimitedFilter.callerKey(identity));
    }

    @Test
    void callerKey_fail_closed_when_identity_missing_or_blank() {
        assertNull(RateLimitedFilter.callerKey(null));
        assertNull(RateLimitedFilter.callerKey(QuarkusSecurityIdentity.builder().setAnonymous(true).build()));
        assertNull(RateLimitedFilter.callerKey(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("  "))
                .addRole("dashboard")
                .build()));
    }

    private static final class TokenPrincipal implements JsonWebToken {
        private final String name;
        private final String subject;

        private TokenPrincipal(String name, String subject) {
            this.name = name;
            this.subject = subject;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getSubject() {
            return subject;
        }

        @Override
        public Set<String> getClaimNames() {
            return Set.of("sub");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getClaim(String claimName) {
            return "sub".equals(claimName) ? (T) subject : null;
        }
    }
}
