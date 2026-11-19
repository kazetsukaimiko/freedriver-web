package io.freedriver.autonomymcp;

import io.quarkus.oidc.client.runtime.DisabledOidcClientException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DashboardExchangeGrantTest {

    @Test
    void a_caller_who_is_not_a_verified_token_sends_no_scope() {
        DashboardExchangeGrant grant = DashboardExchangeGrant.of("caller-ada", "caller-ada");

        assertEquals(Map.of("subject_token", "caller-ada"), grant.parameters());
        assertFalse(grant.parameters().containsKey("scope"));
    }

    @Test
    void a_missing_or_non_string_scope_claim_sends_no_scope() {
        assertEquals("", ApplianceGrants.fromPrincipal(new ScopeToken(null)));
        assertEquals("", ApplianceGrants.fromPrincipal(new ScopeToken(7)));
        assertEquals("", ApplianceGrants.fromPrincipal(new ScopeToken("   ")));
        assertEquals("", ApplianceGrants.fromPrincipal(new ScopeToken("openid profile")));
    }

    @Test
    void only_the_named_appliance_scopes_are_sent_and_in_grant_order() {
        ScopeToken token = new ScopeToken(
                "profile appliance_data_write appliance_read openid appliance_read appliance_state_write");

        DashboardExchangeGrant grant = DashboardExchangeGrant.of("subject-1", token);

        assertEquals("appliance_read appliance_state_write appliance_data_write", grant.scope());
        assertEquals(Map.of(
                "subject_token", "subject-1",
                "scope", "appliance_read appliance_state_write appliance_data_write"), grant.parameters());
    }

    @Test
    void one_appliance_scope_drops_every_other_name() {
        DashboardExchangeGrant grant = DashboardExchangeGrant.of(
                "subject-1", new ScopeToken("openid appliance_read email"));

        assertEquals(Map.of("subject_token", "subject-1", "scope", "appliance_read"), grant.parameters());
    }

    @Test
    void a_blank_scope_cannot_be_sent() {
        assertThrows(IllegalArgumentException.class, () -> new DashboardExchangeGrant("subject-1", "  "));
        assertThrows(IllegalArgumentException.class, () -> new DashboardExchangeGrant("  ", null));
    }

    @Test
    void the_parameter_map_is_not_the_shared_grant_options() {
        Map<String, String> first = DashboardExchangeGrant.of("subject-1", new ScopeToken("appliance_read"))
                .parameters();
        Map<String, String> second = DashboardExchangeGrant.of("subject-1", new ScopeToken("appliance_read"))
                .parameters();

        assertNotSame(first, second);
        assertFalse(first.containsKey("audience"));
        assertFalse(first.containsKey("subject_token_type"));
        assertFalse(first.containsKey("requested_token_type"));
    }

    @Test
    void a_disabled_exchange_client_aborts_with_500_and_a_refusal_with_401() {
        assertEquals(500, DashboardTokenExchange.abortStatus(new DisabledOidcClientException()));
        assertEquals(500, DashboardTokenExchange.abortStatus(
                new IllegalStateException(new DisabledOidcClientException())));
        assertEquals(401, DashboardTokenExchange.abortStatus(new IllegalStateException("invalid_request")));
        assertEquals(401, DashboardTokenExchange.abortStatus(null));
    }

    private static final class ScopeToken implements JsonWebToken {
        private final Object scope;

        private ScopeToken(Object scope) {
            this.scope = scope;
        }

        @Override
        public String getName() {
            return "ada";
        }

        @Override
        public Set<String> getClaimNames() {
            return scope == null ? Set.of() : Set.of("scope");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getClaim(String claimName) {
            return "scope".equals(claimName) ? (T) scope : null;
        }
    }
}
