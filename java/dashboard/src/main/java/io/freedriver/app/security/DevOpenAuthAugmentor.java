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
 * The single {@code %dev} auth path, built into the dev profile only via {@code @IfBuildProfile("dev")}.
 * quarkus:dev runs standalone, so this grants principal {@code dev} and role {@code dashboard}
 * to anonymous callers and {@code @RolesAllowed} runs as it does in prod.
 * Authorization stays on in dev mode (see application.properties).
 * Tests authenticate with {@code @TestSecurity} under {@code %test}.
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
