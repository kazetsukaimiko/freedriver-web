package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.Actors;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;

/**
 * grok.com, the host, and the portal's service account are different clients of this same service.
 * When OIDC is on, each client id is accepted only with its own role: the dispatcher client with
 * {@code dispatcher}, the device client with {@code device}, and the portal client with
 * {@code dispatcher-admin}. Anything else is 403.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION)
public class AllowedClientFilter implements ContainerRequestFilter {

    private final SecurityIdentity identity;
    private final boolean oidcEnabled;
    private final String dispatcherClient;
    private final String deviceClient;
    private final String portalClient;

    @Inject
    public AllowedClientFilter(
            SecurityIdentity identity,
            @ConfigProperty(name = "quarkus.oidc.enabled") boolean oidcEnabled,
            DispatcherConfig config) {
        this.identity = identity;
        this.oidcEnabled = oidcEnabled;
        this.dispatcherClient = config.client().dispatcher();
        this.deviceClient = config.client().device();
        this.portalClient = config.client().portal();
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (allowed(
                oidcEnabled,
                identity == null || identity.isAnonymous(),
                azp(identity),
                identity != null && identity.hasRole(Actors.DISPATCHER),
                identity != null && identity.hasRole(Actors.DEVICE),
                identity != null && identity.hasRole(Actors.DISPATCHER_ADMIN),
                dispatcherClient,
                deviceClient,
                portalClient)) {
            return;
        }
        request.abortWith(Response.status(Response.Status.FORBIDDEN).build());
    }

    static boolean allowed(
            boolean oidcEnabled,
            boolean anonymous,
            String azp,
            boolean dispatcherRole,
            boolean deviceRole,
            boolean adminRole,
            String dispatcherClient,
            String deviceClient,
            String portalClient) {
        if (!oidcEnabled || anonymous) {
            return true;
        }
        if (dispatcherRole && dispatcherClient != null && dispatcherClient.equals(azp)) {
            return true;
        }
        if (deviceRole && deviceClient != null && deviceClient.equals(azp)) {
            return true;
        }
        return adminRole && portalClient != null && portalClient.equals(azp);
    }

    private static String azp(SecurityIdentity identity) {
        if (identity == null) {
            return null;
        }
        Principal principal = identity.getPrincipal();
        if (principal instanceof JsonWebToken jwt) {
            Object claim = jwt.getClaim("azp");
            if (claim instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
