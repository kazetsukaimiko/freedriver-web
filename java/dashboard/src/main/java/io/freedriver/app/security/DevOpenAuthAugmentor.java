package io.freedriver.app.security;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The single {@code %dev} auth path. {@code @IfBuildProfile("dev")} only — never test or prod.
 * quarkus:dev has no Keycloak. This grants principal {@code dev} and role {@code dashboard}
 * to anonymous callers so {@code @RolesAllowed} actually runs.
 * Authorization stays on: do not set {@code quarkus.security.auth.enabled-in-dev-mode=false}.
 * Tests use {@code %test} and {@code @TestSecurity} — they 401/403 without it.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevOpenAuthAugmentor implements SecurityIdentityAugmentor {

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity != null && !identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("dev"))
                .addRole("dashboard")
                .build());
    }
}
