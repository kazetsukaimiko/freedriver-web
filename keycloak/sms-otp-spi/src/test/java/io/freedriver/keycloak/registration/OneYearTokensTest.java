package io.freedriver.keycloak.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.RealmModel;
import org.keycloak.services.clientregistration.ClientRegistrationContext;
import org.keycloak.services.clientregistration.ClientRegistrationProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OneYearTokensTest {

    static final String GROK = "https://grok.com/connectors-oauth-exchange-code/";
    static final String LIFESPAN = "31536000";

    private ComponentModel component;
    private ClientModel client;
    private ClientScopeModel offline;
    private OneYearTokens policy;

    @BeforeEach
    void policyForGrokCallback() {
        component = mock(ComponentModel.class);
        when(component.get(OneYearTokensFactory.ACCESS_TOKEN_LIFESPAN)).thenReturn(LIFESPAN);
        when(component.get(OneYearTokensFactory.REDIRECT_URI)).thenReturn(GROK);
        client = mock(ClientModel.class);
        RealmModel realm = mock(RealmModel.class);
        offline = mock(ClientScopeModel.class);
        when(offline.getId()).thenReturn("offline-id");
        when(offline.getName()).thenReturn(OneYearTokens.OFFLINE_ACCESS);
        when(offline.getProtocol()).thenReturn(OneYearTokens.OPENID_CONNECT);
        when(client.getRealm()).thenReturn(realm);
        when(realm.getClientScopesStream()).thenAnswer(invocation -> Stream.of(offline));
        policy = new OneYearTokens(component);
    }

    @Test
    void afterRegisterSetsBothWhenRedirectMatches() {
        when(client.getRedirectUris()).thenReturn(Set.of(GROK, "https://x.ai/also"));
        when(client.getClientScopes(false)).thenReturn(Map.of(OneYearTokens.OFFLINE_ACCESS, offline));
        when(client.getClientScopes(true)).thenReturn(Map.of());

        policy.afterRegister(mock(ClientRegistrationContext.class), client);

        verify(client).setAttribute(OneYearTokens.ACCESS_TOKEN_LIFESPAN_ATTRIBUTE, LIFESPAN);
        verify(client).removeClientScope(offline);
        verify(client).addClientScope(offline, true);
    }

    @Test
    void afterRegisterLeavesADifferentRedirectAlone() {
        when(client.getRedirectUris()).thenReturn(Set.of("https://x.ai/not-the-grok-callback"));

        policy.afterRegister(mock(ClientRegistrationContext.class), client);

        verify(client, never()).setAttribute(any(), any());
        verify(client, never()).addClientScope(any(), anyBoolean());
        verify(client, never()).removeClientScope(any());
    }

    @Test
    void otherHooksDoNothing() throws Exception {
        ClientRegistrationContext context = mock(ClientRegistrationContext.class);
        ClientRegistrationProvider provider = mock(ClientRegistrationProvider.class);

        policy.beforeRegister(context);
        policy.beforeUpdate(context, client);
        policy.afterUpdate(context, client);
        policy.beforeView(provider, client);
        policy.beforeDelete(provider, client);

        verify(client, never()).setAttribute(any(), any());
        verify(client, never()).addClientScope(any(), anyBoolean());
    }

    @Test
    void factoryIdAndConfig() throws IOException {
        OneYearTokensFactory factory = new OneYearTokensFactory();
        assertEquals("freedriver-one-year-tokens", factory.getId());
        assertEquals(
                OneYearTokensFactory.ACCESS_TOKEN_LIFESPAN,
                factory.getConfigProperties().get(0).getName());
        assertEquals(OneYearTokensFactory.REDIRECT_URI, factory.getConfigProperties().get(1).getName());
        String resource = "META-INF/services/org.keycloak.services.clientregistration.policy.ClientRegistrationPolicyFactory";
        try (InputStream in = OneYearTokensFactory.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null);
            String listed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(listed.contains(OneYearTokensFactory.class.getName()));
        }
    }

    @Test
    void afterRegisterSkipsAddWhenOfflineAccessIsAlreadyDefault() {
        when(client.getRedirectUris()).thenReturn(Set.of(GROK));
        Map<String, ClientScopeModel> defaults = new LinkedHashMap<>();
        defaults.put(OneYearTokens.OFFLINE_ACCESS, offline);
        when(client.getClientScopes(true)).thenReturn(defaults);

        policy.afterRegister(mock(ClientRegistrationContext.class), client);

        verify(client).setAttribute(OneYearTokens.ACCESS_TOKEN_LIFESPAN_ATTRIBUTE, LIFESPAN);
        verify(client, never()).addClientScope(any(), anyBoolean());
        verify(client, never()).removeClientScope(any());
    }
}
