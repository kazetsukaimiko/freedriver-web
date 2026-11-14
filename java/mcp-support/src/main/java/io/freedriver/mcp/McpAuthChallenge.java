package io.freedriver.mcp;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An anonymous MCP request ends with 401 and a Bearer {@code resource_metadata} challenge. It does
 * not authenticate and it does not redirect to a login page.
 */
@ApplicationScoped
public class McpAuthChallenge implements HttpAuthenticationMechanism {

    private final McpEndpoint endpoint;

    @Inject
    public McpAuthChallenge(McpEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        if (!endpoint.isMcpPath(context.normalizedPath()) && !endpoint.isMcpPath(context.request().path())) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", endpoint.challenge()));
    }

    /** Higher than the OIDC bearer mechanism, so this challenge wins on the MCP paths. */
    @Override
    public int getPriority() {
        return DEFAULT_PRIORITY + 1000;
    }
}
