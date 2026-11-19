package io.freedriver.keycloak.registration;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.clientregistration.policy.AbstractClientRegistrationPolicyFactory;
import org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy;

import java.util.List;

/**
 * Anonymous registration policy. The provision script sets both properties
 * from the same one-year constant the repair loop uses.
 */
public final class OneYearTokensFactory extends AbstractClientRegistrationPolicyFactory {

    public static final String PROVIDER_ID = "freedriver-one-year-tokens";

    static final String ACCESS_TOKEN_LIFESPAN = "access-token-lifespan";
    static final String REDIRECT_URI = "redirect-uri";

    @Override
    public ClientRegistrationPolicy create(KeycloakSession session, ComponentModel model) {
        return new OneYearTokens(model);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Sets access.token.lifespan and default offline_access when a redirect URI matches.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty(
                        ACCESS_TOKEN_LIFESPAN,
                        "Access token lifespan",
                        "Seconds stored on access.token.lifespan.",
                        ProviderConfigProperty.STRING_TYPE,
                        null),
                new ProviderConfigProperty(
                        REDIRECT_URI,
                        "Redirect URI",
                        "Exact redirect URI that selects the client.",
                        ProviderConfigProperty.STRING_TYPE,
                        null));
    }
}
