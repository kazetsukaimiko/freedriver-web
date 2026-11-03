package io.freedriver.app.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasskeyActionTest {

    static final String AUTH =
            "https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/auth?client_id=freedriver-api";

    @Test
    void kcActionIsWebauthnRegisterPasswordlessOnTheAuthorizationEndpoint() {
        assertTrue(PasskeyAction.kcAction(AUTH, PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS, null));
        assertTrue(PasskeyAction.kcAction(AUTH, PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS, "Default"));
        assertTrue(PasskeyAction.kcAction(AUTH, PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS, ""));
    }

    @Test
    void kcActionStaysOffForPasswordLogoutMcpAndGrokRedirects() {
        assertFalse(PasskeyAction.kcAction(AUTH, PasswordAction.UPDATE_PASSWORD, null));
        assertFalse(PasskeyAction.kcAction(
                "https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/logout",
                PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS,
                null));
        assertFalse(PasskeyAction.kcAction(AUTH, PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS, "mcp"));
        assertFalse(PasskeyAction.kcAction(
                "https://grok.com/connectors-oauth-exchange-code/",
                PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS,
                null));
        assertFalse(PasskeyAction.kcAction(AUTH, null, null));
    }
}
