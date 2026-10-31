package io.freedriver.app.security;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The single {@code %dev} auth path, built into the dev profile only via {@code @IfBuildProfile("dev")}.
 * quarkus:dev runs standalone, so this grants principal {@code dev} and role {@code dashboard}
 * to anonymous callers and {@code @RolesAllowed} runs as it does in prod.
 * Authorization stays on in dev mode (see application.properties).
 * Tests authenticate with {@code @TestSecurity} under {@code %test}.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevOpenAuthAugmentor implements SecurityIdentityAugmentor {

    @Inject
    Instance<RoutingContext> routing;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity != null && !identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        if (mcpPath()) {
            return identity == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(identity);
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("dev"))
                .addRole("dashboard")
                .build());
    }

    private boolean mcpPath() {
        if (routing == null || !routing.isResolvable()) {
            return false;
        }
        RoutingContext rc = routing.get();
        return rc != null && rc.request() != null && McpPaths.matches(rc.request().path());
    }
}
