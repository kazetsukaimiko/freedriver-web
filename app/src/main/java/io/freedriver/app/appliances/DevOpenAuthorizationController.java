package io.freedriver.app.appliances;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.security.spi.runtime.AuthorizationController;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.interceptor.Interceptor;

/**
 * quarkus:dev has no Keycloak. Skip {@code @RolesAllowed} so the fake map is reachable.
 * %test and prod keep authorization on.
 */
@Alternative
@Priority(Interceptor.Priority.LIBRARY_AFTER)
@ApplicationScoped
@IfBuildProfile("dev")
public class DevOpenAuthorizationController extends AuthorizationController {

    @Override
    public boolean isAuthorizationEnabled() {
        return false;
    }
}
