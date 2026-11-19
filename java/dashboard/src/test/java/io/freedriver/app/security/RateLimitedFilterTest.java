package io.freedriver.app.security;

import io.freedriver.app.api.ErrorMappers;
import io.freedriver.app.appliances.TestAppliancesConfig;
import io.freedriver.dashboard.api.RateLimitBucket;
import io.freedriver.ratelimit.MemoryHitWindow;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitedFilterTest {

    @Test
    void callerKey_prefers_oidc_sub_attribute() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("ada"))
                .addAttribute("sub", "oidc-sub-1")
                .addRole("dashboard")
                .build();
        assertEquals("oidc-sub-1", RateLimitedFilter.callerKey(identity));
    }

    @Test
    void callerKey_uses_jwt_subject_when_attribute_absent() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new TokenPrincipal("ada", "jwt-sub-9"))
                .addRole("dashboard")
                .build();
        assertEquals("jwt-sub-9", RateLimitedFilter.callerKey(identity));
    }

    @Test
    void callerKey_falls_back_to_principal_name() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("ada"))
                .addRole("dashboard")
                .build();
        assertEquals("ada", RateLimitedFilter.callerKey(identity));
    }

    @Test
    void oidcSubject_is_attribute_or_jwt_subject_not_the_principal_name() {
        var namedOnly = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("ada"))
                .addRole("dashboard")
                .build();
        assertNull(OidcSubjects.subject(namedOnly));

        var attribute = QuarkusSecurityIdentity.builder()
                .setPrincipal(new TokenPrincipal("ada", "jwt-sub-9"))
                .addAttribute("sub", "oidc-sub-1")
                .addRole("dashboard")
                .build();
        assertEquals("oidc-sub-1", OidcSubjects.subject(attribute));

        var jwt = QuarkusSecurityIdentity.builder()
                .setPrincipal(new TokenPrincipal("ada", "jwt-sub-9"))
                .addRole("dashboard")
                .build();
        assertEquals("jwt-sub-9", OidcSubjects.subject(jwt));
        assertNull(OidcSubjects.subject(null));
        assertNull(OidcSubjects.subject(QuarkusSecurityIdentity.builder().setAnonymous(true).build()));
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

    @Test
    void a_caller_with_no_key_is_refused_with_an_empty_429() {
        MemoryHitWindow hits = new MemoryHitWindow();
        var blank = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("  "))
                .addRole("portal-admin")
                .build();
        assertFalse(blank.isAnonymous());
        RateLimitedFilter filter = new RateLimitedFilter(RateLimitBucket.INVITES, limiter(hits), blank);

        RateLimitedException refused = assertThrows(RateLimitedException.class, () -> filter.filter(null));

        var response = new ErrorMappers().rateLimited(refused);
        assertEquals(429, response.getStatus());
        assertNull(response.getEntity());
        assertEquals(0, hits.count("invites:  ", 60_000));
        assertEquals(0, hits.count("invites:", 60_000));
    }

    @Test
    void the_filter_spends_its_own_bucket_for_the_caller() {
        MemoryHitWindow hits = new MemoryHitWindow();
        SecurityIdentity ada = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("ada"))
                .addRole("portal-admin")
                .build();
        RateLimitedFilter invites = new RateLimitedFilter(RateLimitBucket.INVITES, limiter(hits), ada);
        RateLimitedFilter phones = new RateLimitedFilter(RateLimitBucket.PHONES, limiter(hits), ada);

        invites.filter(null);
        invites.filter(null);
        assertThrows(RateLimitedException.class, () -> invites.filter(null));
        phones.filter(null);

        assertEquals(2, hits.count("invites:ada", 60_000));
        assertEquals(1, hits.count("phones:ada", 60_000));
    }

    private static CallerRateLimiter limiter(MemoryHitWindow hits) {
        return new CallerRateLimiter(TestAppliancesConfig.of(Map.of(
                "rate-limit.permits", "2", "rate-limit.window", "60s")), hits);
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
