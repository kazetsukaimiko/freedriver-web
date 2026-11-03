package io.freedriver.app.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordActionTest {

    static final String AUTH =
            "https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/auth?client_id=freedriver-api";

    @Test
    void startPathIsLoginOnTheBffClient() {
        assertEquals("/login", PasswordAction.url("freedriver-api"));
        assertFalse(PasswordAction.url("freedriver-api").contains("grok"));
        assertFalse(PasswordAction.url("freedriver-api").contains("password="));
        assertFalse(PasswordAction.url("freedriver-api").contains("twilio"));
    }

    @Test
    void grokBotClientIsNotAPasswordActionClient() {
        assertThrows(IllegalArgumentException.class, () -> PasswordAction.url("freedriver-grok-mcp"));
        assertThrows(IllegalArgumentException.class, () -> PasswordAction.url(""));
        assertThrows(IllegalArgumentException.class, () -> PasswordAction.url(null));
    }

    @Test
    void kcActionIsUpdatePasswordOnTheAuthorizationEndpoint() {
        assertTrue(PasswordAction.kcAction(AUTH, PasswordAction.UPDATE_PASSWORD, null));
        assertTrue(PasswordAction.kcAction(AUTH, PasswordAction.UPDATE_PASSWORD, "Default"));
        assertTrue(PasswordAction.kcAction(AUTH, PasswordAction.UPDATE_PASSWORD, ""));
    }

    @Test
    void kcActionStaysOffForLogoutMcpAndGrokRedirects() {
        assertFalse(PasswordAction.kcAction(
                "https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/logout",
                PasswordAction.UPDATE_PASSWORD,
                null));
        assertFalse(PasswordAction.kcAction(AUTH, PasswordAction.UPDATE_PASSWORD, "mcp"));
        assertFalse(PasswordAction.kcAction(
                "https://grok.com/connectors-oauth-exchange-code/",
                PasswordAction.UPDATE_PASSWORD,
                null));
        assertFalse(PasswordAction.kcAction(AUTH, "UPDATE_PROFILE", null));
        assertFalse(PasswordAction.kcAction(AUTH, null, null));
    }
}
