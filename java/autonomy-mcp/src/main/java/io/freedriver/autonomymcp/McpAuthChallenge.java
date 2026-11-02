package io.freedriver.autonomymcp;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Anonymous {@code /autonomy/mcp} ends with 401 and a Bearer resource_metadata
 * challenge. It does not authenticate and it does not redirect to login.
 */
@ApplicationScoped
public class McpAuthChallenge implements HttpAuthenticationMechanism {

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        if (!McpProtectedResource.isMcpPath(context.normalizedPath())
                && !McpProtectedResource.isMcpPath(context.request().path())) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", McpProtectedResource.CHALLENGE));
    }

    @Override
    public int getPriority() {
        return DEFAULT_PRIORITY + 1000;
    }
}
