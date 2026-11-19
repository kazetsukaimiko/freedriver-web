package io.freedriver.autonomymcp;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.oidc.client.runtime.DisabledOidcClientException;
import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestContext;
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestFilter;

/**
 * Exchanges the caller's access token for a dashboard token and sets that bearer on the request.
 * Registered, after this design is signed off, on {@link DashboardAppliances} and
 * {@link DashboardMcpOps} in place of {@code @AccessToken}. It is not a {@code @Provider}: a
 * global filter would exchange tokens for every REST client.
 * <p>
 * {@code AccessTokenRequestReactiveFilter.exchangeToken} is private and calls
 * {@code getTokens} with only {@code subject_token}, so the token-propagation extension cannot
 * add the caller's appliance scopes. This filter calls
 * {@code OidcClient#getTokens(Map)} on the {@code dashboard-exchange} client with
 * {@link DashboardExchangeGrant#parameters()}. The subject token is the credential's token.
 * The scope comes from the verified {@link SecurityIdentity} principal, never from a parse of
 * that credential.
 * <p>
 * A missing, blank, or ambiguous credential aborts with 401 before Keycloak is called. Keycloak
 * refusing the exchange aborts with 401. A disabled exchange client aborts with 500. Both are
 * the statuses {@link DashboardCalls} already turns into a dashboard answer.
 */
@ApplicationScoped
public class DashboardTokenExchange implements ResteasyReactiveClientRequestFilter {

    private final Instance<TokenCredential> accessToken;
    private final SecurityIdentity identity;
    private final OidcClient exchange;

    @Inject
    public DashboardTokenExchange(
            Instance<TokenCredential> accessToken,
            SecurityIdentity identity,
            @NamedOidcClient("dashboard-exchange") OidcClient exchange) {
        this.accessToken = accessToken;
        this.identity = identity;
        this.exchange = exchange;
    }

    @Override
    public void filter(ResteasyReactiveClientRequestContext request) {
        String subjectToken = callerToken();
        if (subjectToken == null) {
            request.abortWith(Response.status(401).build());
            return;
        }
        DashboardExchangeGrant grant = DashboardExchangeGrant.of(subjectToken, identity.getPrincipal());
        request.suspend();
        exchange.getTokens(grant.parameters()).subscribe().with(
                tokens -> sent(request, tokens),
                failure -> {
                    request.abortWith(Response.status(abortStatus(failure)).build());
                    request.resume();
                });
    }

    /** 500 when the exchange client is off, otherwise 401. */
    static int abortStatus(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof DisabledOidcClientException) {
                return 500;
            }
        }
        return 401;
    }

    private void sent(ResteasyReactiveClientRequestContext request, Tokens tokens) {
        if (tokens == null || tokens.getAccessToken() == null || tokens.getAccessToken().isBlank()) {
            request.abortWith(Response.status(401).build());
        } else {
            request.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.getAccessToken());
        }
        request.resume();
    }

    private String callerToken() {
        if (accessToken.isUnsatisfied() || accessToken.isAmbiguous()) {
            return null;
        }
        TokenCredential credential = accessToken.get();
        if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) {
            return null;
        }
        return credential.getToken();
    }
}
