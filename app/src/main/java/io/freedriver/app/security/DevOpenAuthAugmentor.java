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
 * %dev only: quarkus:dev works with no Keycloak. Grants {@code dashboard} to anonymous callers.
 * Tests use %test and {@code @TestSecurity} instead — auth is required there.
 * Do not add an AuthorizationController: {@code %dev.quarkus.security.auth.enabled-in-dev-mode=false}
 * already installs Quarkus's DevModeDisabledAuthorizationController; a second one made quarkus:dev fail to start.
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
