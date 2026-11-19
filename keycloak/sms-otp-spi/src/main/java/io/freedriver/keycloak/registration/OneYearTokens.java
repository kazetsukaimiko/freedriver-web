package io.freedriver.keycloak.registration;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.RealmModel;
import org.keycloak.services.clientregistration.ClientRegistrationContext;
import org.keycloak.services.clientregistration.ClientRegistrationProvider;
import org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy;
import org.keycloak.services.clientregistration.policy.ClientRegistrationPolicyException;

import java.util.Map;
import java.util.Set;

/**
 * After an anonymous registration, a client whose redirect URIs include the
 * configured callback gets the two settings a one-year token needs.
 */
public final class OneYearTokens implements ClientRegistrationPolicy {

    static final String ACCESS_TOKEN_LIFESPAN_ATTRIBUTE = "access.token.lifespan";
    static final String OFFLINE_ACCESS = "offline_access";
    static final String OPENID_CONNECT = "openid-connect";

    private final ComponentModel component;

    public OneYearTokens(ComponentModel component) {
        this.component = component;
    }

    @Override
    public void beforeRegister(ClientRegistrationContext context) throws ClientRegistrationPolicyException {
    }

    @Override
    public void afterRegister(ClientRegistrationContext context, ClientModel client) {
        if (client == null || !redirectMatches(client)) {
            return;
        }
        String lifespan = component.get(OneYearTokensFactory.ACCESS_TOKEN_LIFESPAN);
        if (lifespan != null && !lifespan.isBlank()) {
            client.setAttribute(ACCESS_TOKEN_LIFESPAN_ATTRIBUTE, lifespan);
        }
        moveOfflineAccessToDefault(client);
    }

    @Override
    public void beforeUpdate(ClientRegistrationContext context, ClientModel client)
            throws ClientRegistrationPolicyException {
    }

    @Override
    public void afterUpdate(ClientRegistrationContext context, ClientModel client) {
    }

    @Override
    public void beforeView(ClientRegistrationProvider provider, ClientModel client)
            throws ClientRegistrationPolicyException {
    }

    @Override
    public void beforeDelete(ClientRegistrationProvider provider, ClientModel client)
            throws ClientRegistrationPolicyException {
    }

    private boolean redirectMatches(ClientModel client) {
        String wanted = component.get(OneYearTokensFactory.REDIRECT_URI);
        if (wanted == null || wanted.isBlank()) {
            return false;
        }
        Set<String> uris = client.getRedirectUris();
        return uris != null && uris.contains(wanted);
    }

    private void moveOfflineAccessToDefault(ClientModel client) {
        RealmModel realm = client.getRealm();
        if (realm == null) {
            return;
        }
        ClientScopeModel offline = realm.getClientScopesStream()
                .filter(scope -> OFFLINE_ACCESS.equals(scope.getName()) && OPENID_CONNECT.equals(scope.getProtocol()))
                .findFirst()
                .orElse(null);
        if (offline == null) {
            return;
        }
        Map<String, ClientScopeModel> defaults = client.getClientScopes(true);
        if (containsScope(defaults, offline)) {
            return;
        }
        Map<String, ClientScopeModel> optional = client.getClientScopes(false);
        if (containsScope(optional, offline)) {
            client.removeClientScope(offline);
        }
        client.addClientScope(offline, true);
    }

    private static boolean containsScope(Map<String, ClientScopeModel> scopes, ClientScopeModel wanted) {
        if (scopes == null) {
            return false;
        }
        for (ClientScopeModel scope : scopes.values()) {
            if (scope != null && wanted.getId().equals(scope.getId())) {
                return true;
            }
        }
        return false;
    }
}
