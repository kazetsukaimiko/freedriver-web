package io.freedriver.dispatcher;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The HTTP security layer ends anonymous {@code /mcp} with 401 before JAX-RS.
 * This challenge is the Bearer resource_metadata URL. It does not authenticate.
 */
@ApplicationScoped
public class McpAuthChallenge implements HttpAuthenticationMechanism {

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        if (!mcp(context)) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", McpProtectedResource.CHALLENGE));
    }

    @Override
    public int getPriority() {
        // Higher than the OIDC bearer mechanism so this challenge wins on /mcp when OIDC is enabled.
        return DEFAULT_PRIORITY + 1000;
    }

    private static boolean mcp(RoutingContext context) {
        return McpProtectedResource.isMcpPath(context.normalizedPath())
                || McpProtectedResource.isMcpPath(context.request().path());
    }
}
