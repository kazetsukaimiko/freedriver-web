package io.freedriver.app.account;

/**
 * Keycloak application-initiated {@code UPDATE_PASSWORD}. The BFF login hop
 * carries {@code kc_action}; this class never holds a password.
 */
public final class PasswordAction {

    public static final String COOKIE = "freedriver.kc-action";

    public static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    /** Quarkus OIDC starts the authorization request from this path. */
    public static final String START_PATH = "/login";

    static final String GROK_CLIENT = "freedriver-grok-mcp";

    private PasswordAction() {}

    /**
     * {@code clientId} is the BFF client. Grok Bot is not a password-action client.
     */
    public static String url(String clientId) {
        if (clientId == null || clientId.isBlank() || GROK_CLIENT.equals(clientId)) {
            throw new IllegalArgumentException("client");
        }
        return START_PATH;
    }

    /**
     * Add {@code kc_action=UPDATE_PASSWORD} only on the default tenant's
     * authorization redirect when the one-shot cookie is present.
     */
    public static boolean kcAction(String redirectUri, String cookieValue, String tenantId) {
        if (!UPDATE_PASSWORD.equals(cookieValue)) {
            return false;
        }
        if (redirectUri == null || !redirectUri.contains("/protocol/openid-connect/auth")) {
            return false;
        }
        if (tenantId != null && !tenantId.isBlank() && !"Default".equals(tenantId)) {
            return false;
        }
        return true;
    }
}
