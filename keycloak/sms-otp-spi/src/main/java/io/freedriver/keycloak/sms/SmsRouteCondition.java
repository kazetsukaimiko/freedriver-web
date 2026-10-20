package io.freedriver.keycloak.sms;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Matches only when {@code freedriver.sms.route} equals the configured route,
 * {@code phone} or {@code username}. Any other config or note does not match.
 */
public final class SmsRouteCondition implements ConditionalAuthenticator {

    @Override
    public boolean matchCondition(AuthenticationFlowContext context) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        if (config == null || config.getConfig() == null) {
            return false;
        }
        String expected = config.getConfig().get(SmsRouteConditionFactory.CONF_ROUTE);
        if (!PhoneUsernameRouter.ROUTE_PHONE.equals(expected)
                && !PhoneUsernameRouter.ROUTE_USERNAME.equals(expected)) {
            return false;
        }
        String actual = context.getAuthenticationSession().getAuthNote(PhoneUsernameRouter.NOTE_ROUTE);
        return expected.equals(actual);
    }

    @Override
    public void action(AuthenticationFlowContext context) {}

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public void close() {}
}
