package io.freedriver.app.account;

/**
 * Keycloak application-initiated {@code webauthn-register-passwordless}.
 * RP ID is {@code auth.freedriver.io}. This class never calls WebAuthn.
 */
public final class PasskeyAction {

    public static final String WEBAUTHN_REGISTER_PASSWORDLESS = "webauthn-register-passwordless";

    private PasskeyAction() {}

    /**
     * Add {@code kc_action=webauthn-register-passwordless} only on the default
     * tenant's authorization redirect when the one-shot cookie is present.
     */
    public static boolean kcAction(String redirectUri, String cookieValue, String tenantId) {
        if (!WEBAUTHN_REGISTER_PASSWORDLESS.equals(cookieValue)) {
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
