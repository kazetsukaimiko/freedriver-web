package io.freedriver.keycloak.sms;

import org.keycloak.Config;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Config property {@code route} is {@code phone} or {@code username}.
 * #156 attaches this execution. This provider does not change the live flow.
 */
public final class SmsRouteConditionFactory implements ConditionalAuthenticatorFactory {

    public static final String PROVIDER_ID = "freedriver-sms-route-condition";

    /** Auth-note value this condition matches. {@code phone} or {@code username}. */
    public static final String CONF_ROUTE = "route";

    private static final SmsRouteCondition SINGLETON = new SmsRouteCondition();

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENTS = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    private static final ProviderConfigProperty ROUTE = new ProviderConfigProperty(
            CONF_ROUTE,
            "Route",
            "Match only this value of the auth note freedriver.sms.route.",
            ProviderConfigProperty.LIST_TYPE,
            null,
            PhoneUsernameRouter.ROUTE_PHONE,
            PhoneUsernameRouter.ROUTE_USERNAME);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Condition - Freedriver SMS route";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENTS.clone();
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Conditional subflow runs only when freedriver.sms.route equals the configured "
                + "route, phone or username.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(ROUTE);
    }

    @Override
    public ConditionalAuthenticator getSingleton() {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
