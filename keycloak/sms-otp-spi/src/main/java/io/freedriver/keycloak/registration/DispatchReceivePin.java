package io.freedriver.keycloak.registration;

import org.keycloak.OAuth2Constants;

import java.util.Set;

/**
 * What afterRegister and afterUpdate write on a dispatch_receive client.
 * Applying it removes {@code access.token.lifespan} and leaves {@code publicClient}
 * and the client authenticator as registered.
 */
public record DispatchReceivePin(
        boolean consentRequired,
        String pkceCodeChallengeMethod,
        boolean implicitFlowEnabled,
        boolean directAccessGrantsEnabled,
        boolean serviceAccountsEnabled,
        boolean authorizationServicesEnabled,
        boolean deviceAuthorizationGrantEnabled,
        boolean cibaGrantEnabled,
        boolean tokenExchangeEnabled,
        String offlineSessionIdleTimeout,
        Set<String> defaultScopes,
        Set<String> optionalScopes) {

    public static DispatchReceivePin pinned() {
        return new DispatchReceivePin(
                true,
                OAuth2Constants.PKCE_METHOD_S256,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                DispatchReceivePolicy.OFFLINE_SESSION_IDLE_SECONDS,
                DispatchReceivePolicy.DEFAULT_SCOPES,
                DispatchReceivePolicy.OPTIONAL_SCOPES);
    }
}
