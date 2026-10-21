package io.freedriver.keycloak.sms;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Requirement choices are REQUIRED and DISABLED. An ALTERNATIVE success would
 * end the flow. #156 places this execution REQUIRED inside a subflow, where
 * {@code success()} without a user continues that subflow.
 */
public final class PhoneUsernameRouterFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "freedriver-phone-username-router";

    private static final PhoneUsernameRouter SINGLETON = new PhoneUsernameRouter();

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENTS = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Freedriver phone or username";
    }

    @Override
    public String getReferenceCategory() {
        return "phone-username";
    }

    @Override
    public boolean isConfigurable() {
        return false;
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
        return "Routes a phone number or a username. Sets freedriver.sms.route and, for a phone, "
                + "freedriver.sms.number, then continues without setting the user or sending a text. "
                + "Does not replace freedriver-sms-otp.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
