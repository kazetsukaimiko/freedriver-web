package io.freedriver.keycloak.registration;

import org.keycloak.OAuth2Constants;

import java.util.Set;

/**
 * Names the anonymous registration policy uses for a {@code dispatch_receive} client.
 * The Keycloak policy class reads these and writes the pin from {@link DispatchReceiveRules}.
 */
public final class DispatchReceivePolicy {

    public static final String PROVIDER_ID = "freedriver-dispatch-receive";

    public static final String ROLE = "dispatch-receiver";

    public static final String SCOPE = "dispatch_receive";

    public static final String BASIC = "basic";

    public static final String OFFLINE_ACCESS = "offline_access";

    public static final String PKCE_CHALLENGE_METHOD = "pkce.code.challenge.method";

    public static final String ACCESS_TOKEN_LIFESPAN = "access.token.lifespan";

    public static final String OFFLINE_SESSION_IDLE_TIMEOUT = "client.offline.session.idle.timeout";

    /** Thirty days, on the client, above the job proof and under the realm's offline idle. */
    public static final String OFFLINE_SESSION_IDLE_SECONDS = "2592000";

    public static final String DEVICE_AUTHORIZATION_GRANT = "oauth2.device.authorization.grant.enabled";

    public static final String CIBA_GRANT = "oidc.ciba.grant.enabled";

    public static final String TOKEN_EXCHANGE_GRANT = "standard.token.exchange.enabled";

    public static final Set<String> ALLOWED_SCOPES = Set.of(BASIC, SCOPE, OFFLINE_ACCESS);

    public static final Set<String> DEFAULT_SCOPES = Set.of(BASIC, SCOPE);

    public static final Set<String> OPTIONAL_SCOPES = Set.of(OFFLINE_ACCESS);

    /** An absent grant list passes. Any other grant type is refused. */
    public static final Set<String> ALLOWED_GRANT_TYPES = Set.of(
            OAuth2Constants.AUTHORIZATION_CODE,
            OAuth2Constants.REFRESH_TOKEN);

    private DispatchReceivePolicy() {
    }
}
