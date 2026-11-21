package io.freedriver.keycloak.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.RealmModel;
import org.keycloak.services.clientregistration.ClientRegistrationContext;
import org.keycloak.services.clientregistration.ClientRegistrationProvider;
import org.keycloak.services.clientregistration.policy.ClientRegistrationPolicyFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void otherHooksDoNothing() {
        clearInvocations(client);
        ClientRegistrationContext context = mock(ClientRegistrationContext.class);
        ClientRegistrationProvider provider = mock(ClientRegistrationProvider.class);

        policy.beforeRegister(context);
        policy.beforeUpdate(context, client);
        policy.afterUpdate(context, client);
        policy.beforeView(provider, client);
        policy.beforeDelete(provider, client);

        verifyNoInteractions(client);
    }

    @Test
    void factoryIdAndConfig() {
        OneYearTokensFactory factory = new OneYearTokensFactory();
        assertEquals("freedriver-one-year-tokens", factory.getId());
        assertEquals(
                OneYearTokensFactory.ACCESS_TOKEN_LIFESPAN,
                factory.getConfigProperties().get(0).getName());
        assertEquals(OneYearTokensFactory.REDIRECT_URI, factory.getConfigProperties().get(1).getName());
    }

    @Test
    void serviceFileLoadsEachFactory() throws IOException {
        Path serviceFile = Path.of(
                "src/main/resources/META-INF/services/org.keycloak.services.clientregistration.policy.ClientRegistrationPolicyFactory");
        List<String> lines = Files.readAllLines(serviceFile, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        String factory = OneYearTokensFactory.class.getName();
        assertEquals(1, lines.stream().filter(factory::equals).count());
        URL serviceUrl = serviceFile.toUri().toURL();
        String serviceName =
                "META-INF/services/org.keycloak.services.clientregistration.policy.ClientRegistrationPolicyFactory";
        ClassLoader listedHere = new ClassLoader(OneYearTokensTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (serviceName.equals(name)) {
                    return Collections.enumeration(List.of(serviceUrl));
                }
                return super.getResources(name);
            }
        };
        List<String> loaded = new ArrayList<>();
        ServiceLoader.load(ClientRegistrationPolicyFactory.class, listedHere)
                .forEach(item -> loaded.add(item.getClass().getName()));
        assertEquals(lines, loaded);
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
