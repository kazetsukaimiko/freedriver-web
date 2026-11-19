package io.freedriver.keycloak.registration;

import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchReceiveRulesTest {

    private static final HostResolver DNS = InetAddress::getAllByName;

    @Test
    void openBotPassesAndPins() {
        DispatchReceiveRequest openBot = DispatchReceiveRequest.openBot();

        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null, openBot));
        assertEquals(DispatchReceivePin.pinned(), DispatchReceiveRules.pin(openBot).orElseThrow());
        assertEquals("2592000", DispatchReceivePin.pinned().offlineSessionIdleTimeout());
        assertEquals(Set.of("basic", "dispatch_receive"), DispatchReceivePin.pinned().defaultScopes());
        assertEquals(Set.of("offline_access"), DispatchReceivePin.pinned().optionalScopes());
        assertTrue(DispatchReceivePin.pinned().consentRequired());
        assertEquals(OAuth2Constants.PKCE_METHOD_S256, DispatchReceivePin.pinned().pkceCodeChallengeMethod());
        assertFalse(DispatchReceivePin.pinned().implicitFlowEnabled());
        assertFalse(DispatchReceivePin.pinned().directAccessGrantsEnabled());
        assertFalse(DispatchReceivePin.pinned().serviceAccountsEnabled());
        assertFalse(DispatchReceivePin.pinned().authorizationServicesEnabled());
        assertFalse(DispatchReceivePin.pinned().deviceAuthorizationGrantEnabled());
        assertFalse(DispatchReceivePin.pinned().cibaGrantEnabled());
        assertFalse(DispatchReceivePin.pinned().tokenExchangeEnabled());
        assertEquals(OneYearTokens.ACCESS_TOKEN_LIFESPAN_ATTRIBUTE, DispatchReceivePolicy.ACCESS_TOKEN_LIFESPAN);
        assertEquals(OneYearTokens.OFFLINE_ACCESS, DispatchReceivePolicy.OFFLINE_ACCESS);
    }

    @Test
    void openBotWithRolesInTheScopeIsRefused() {
        DispatchReceiveRequest widened = DispatchReceiveRequest.builder()
                .oidcScope("dispatch_receive offline_access roles")
                .grantTypes(Set.of("authorization_code", "refresh_token"))
                .build();

        assertEquals("scope is refused", refusal(widened));
        assertTrue(DispatchReceiveRules.pin(widened).isPresent());
    }

    @Test
    void scopeListsAndStoredDefaultsSelectTheClient() {
        assertTrue(DispatchReceiveRules.isDispatchReceive(
                DispatchReceiveRequest.builder().defaultClientScopes(Set.of("dispatch_receive")).build()));
        assertTrue(DispatchReceiveRules.isDispatchReceive(
                DispatchReceiveRequest.builder().optionalClientScopes(Set.of("dispatch_receive")).build()));
        DispatchReceiveRequest stored = DispatchReceiveRequest.builder()
                .storedDefaultScopes(Set.of("dispatch_receive"))
                .build();
        assertTrue(DispatchReceiveRules.isDispatchReceive(stored));
        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null, stored));
    }

    @Test
    void storedOptionalScopeDoesNotSelectTheClient() {
        DispatchReceiveRequest storedOptional = DispatchReceiveRequest.builder()
                .grantTypes(Set.of(OAuth2Constants.PASSWORD))
                .build();

        assertFalse(DispatchReceiveRules.isDispatchReceive(storedOptional));
        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null, storedOptional));
        assertTrue(DispatchReceiveRules.storedHoldsScope(Set.of(), Set.of("dispatch_receive")));
        assertTrue(DispatchReceiveRules.storedHoldsScope(Set.of("dispatch_receive"), Set.of()));
        assertFalse(DispatchReceiveRules.storedHoldsScope(Set.of("roles"), Set.of("profile")));
        assertFalse(DispatchReceiveRules.storedHoldsScope(null, null));
    }

    @Test
    void aNearNameIsNotTheScope() {
        DispatchReceiveRequest near = DispatchReceiveRequest.builder()
                .oidcScope("dispatch_receive_extra")
                .grantTypes(Set.of(OAuth2Constants.PASSWORD))
                .build();

        assertFalse(DispatchReceiveRules.isDispatchReceive(near));
        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null, near));
    }

    @Test
    void fetchUrlsAreRefusedOnAPublicHost() {
        assertEquals("jwks_uri is refused", check(
                new FetchUrls("https://example.com/jwks.json", null, List.of(), null),
                null, null, DispatchReceiveRequest.openBot()).orElseThrow());
        assertEquals("sector_identifier_uri is refused", check(
                new FetchUrls(null, "https://example.com/sector.json", List.of(), null),
                null, null, plain()).orElseThrow());
        assertEquals("request_uris is refused", check(
                new FetchUrls(null, null, List.of("https://example.com/request"), null),
                null, null, plain()).orElseThrow());
        assertEquals("backchannel_logout_uri is refused", check(
                new FetchUrls(null, null, List.of(), "https://example.com/logout"),
                null, null, plain()).orElseThrow());
        assertEquals(Optional.empty(), check(
                new FetchUrls("  ", "", List.of(""), " "),
                null, null, plain()));
    }

    @Test
    void rootAndAdminOnALocalAddressAreRefused() throws Exception {
        DispatchReceiveRequest openBot = DispatchReceiveRequest.openBot();
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://127.1/", null, openBot).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://[::ffff:127.0.0.1]/", null, openBot).orElseThrow());
        assertEquals("adminUrl is refused", check(FetchUrls.none(), null, "http://localhost/", openBot).orElseThrow());
        assertEquals("adminUrl is refused", check(FetchUrls.none(), null, "http://[::1]/", openBot).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://169.254.169.254/", null, openBot).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://10.0.0.1/", null, openBot).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://0.0.0.0/", null, openBot).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://127.0.0.1:8081/", null, plain()).orElseThrow());

        HostResolver privateName = host -> new InetAddress[] {InetAddress.getByName("10.0.0.1")};
        assertEquals("rootUrl is refused",
                DispatchReceiveRules.before(FetchUrls.none(), "https://private.test/admin", null, privateName, openBot)
                        .orElseThrow());
        HostResolver mixed = host -> new InetAddress[] {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1")
        };
        assertEquals("adminUrl is refused",
                DispatchReceiveRules.before(FetchUrls.none(), null, "https://mixed.example/", mixed, plain())
                        .orElseThrow());
    }

    @Test
    void anUnusableHostUrlIsRefused() {
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "ftp://example.com/", null, plain()).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://user@8.8.8.8/", null, plain()).orElseThrow());
        assertEquals("adminUrl is refused", check(FetchUrls.none(), null, "http:///missing", plain()).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "not a uri", null, plain()).orElseThrow());
        HostResolver missing = host -> {
            throw new UnknownHostException(host);
        };
        assertEquals("rootUrl is refused",
                DispatchReceiveRules.before(FetchUrls.none(), "https://missing.example/", null, missing, plain())
                        .orElseThrow());
        HostResolver empty = host -> new InetAddress[0];
        assertEquals("adminUrl is refused",
                DispatchReceiveRules.before(FetchUrls.none(), null, "https://empty.example/", empty, plain())
                        .orElseThrow());
        HostResolver userInfo = host -> {
            throw new AssertionError("resolved " + host);
        };
        assertEquals("rootUrl is refused",
                DispatchReceiveRules.before(FetchUrls.none(), "https://user@admin.example/", null, userInfo, plain())
                        .orElseThrow());
    }

    @Test
    void aPublicRootUrlKeepsItsPathAndQuery() throws Exception {
        HostResolver publicHost = host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        DispatchReceiveRequest openBot = DispatchReceiveRequest.openBot();

        assertEquals(Optional.empty(), DispatchReceiveRules.before(
                FetchUrls.none(),
                "https://admin.example/kc?x=1#section",
                "https://admin.example:443/",
                publicHost,
                openBot));
        assertEquals("admin.example", new java.net.URI("https://admin.example/kc?x=1#section").getHost());
    }

    @Test
    void aClientThatOmitsDispatchReceiveKeepsItsGrants() {
        DispatchReceiveRequest password = DispatchReceiveRequest.builder()
                .grantTypes(Set.of(OAuth2Constants.PASSWORD))
                .directAccessGrantsEnabled(true)
                .protocolMapper(true)
                .accessTokenLifespan(true)
                .pkceCodeChallengeMethod(OAuth2Constants.PKCE_METHOD_PLAIN)
                .consentRequired(false)
                .realmHasBasic(false)
                .realmHasDispatchReceive(false)
                .build();

        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null, password));
        assertTrue(DispatchReceiveRules.pin(password).isEmpty());
    }

    @Test
    void eachWideningOfADispatchReceiveClientIsRefused() {
        assertEquals("grant is refused", refusal(openBot().grantTypes(Set.of(OAuth2Constants.PASSWORD)).build()));
        assertEquals("grant is refused", refusal(openBot().grantTypes(Set.of(OAuth2Constants.IMPLICIT)).build()));
        assertEquals("grant is refused", refusal(openBot().grantTypes(Set.of(OAuth2Constants.CLIENT_CREDENTIALS)).build()));
        assertEquals("grant is refused", refusal(openBot().grantTypes(Set.of(OAuth2Constants.DEVICE_CODE_GRANT_TYPE)).build()));
        assertEquals("grant is refused", refusal(openBot().grantTypes(Set.of(OAuth2Constants.CIBA_GRANT_TYPE)).build()));
        assertEquals("grant is refused", refusal(openBot().grantTypes(Set.of(OAuth2Constants.TOKEN_EXCHANGE_GRANT_TYPE)).build()));
        assertEquals("direct access grants are refused", refusal(openBot().directAccessGrantsEnabled(true).build()));
        assertEquals("implicit flow is refused", refusal(openBot().implicitFlowEnabled(true).build()));
        assertEquals("service accounts are refused", refusal(openBot().serviceAccountsEnabled(true).build()));
        assertEquals("authorization services are refused", refusal(openBot().authorizationServicesEnabled(true).build()));
        assertEquals("device authorization grant is refused", refusal(openBot().deviceAuthorizationGrantEnabled(true).build()));
        assertEquals("ciba grant is refused", refusal(openBot().cibaGrantEnabled(true).build()));
        assertEquals("token exchange is refused", refusal(openBot().tokenExchangeEnabled(true).build()));
        assertEquals("consent is required", refusal(openBot().consentRequired(false).build()));
        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null, openBot().consentRequired(true).build()));
        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null,
                openBot().pkceCodeChallengeMethod(OAuth2Constants.PKCE_METHOD_S256).build()));
        assertEquals(Optional.empty(), check(FetchUrls.none(), null, null,
                openBot().pkceCodeChallengeMethod("  ").build()));
        assertEquals("pkce method must be S256", refusal(openBot().pkceCodeChallengeMethod("plain").build()));
        assertEquals("protocol mapper is refused", refusal(openBot().protocolMapper(true).build()));
        assertEquals("access.token.lifespan is refused", refusal(openBot().accessTokenLifespan(true).build()));
        assertEquals("realm lacks basic or dispatch_receive", refusal(openBot().realmHasBasic(false).build()));
        assertEquals("realm lacks basic or dispatch_receive", refusal(openBot().realmHasDispatchReceive(false).build()));
        assertEquals("scope is refused", refusal(openBot().defaultClientScopes(Set.of("roles")).build()));
        assertEquals("scope is refused", refusal(openBot().optionalClientScopes(Set.of("microprofile-jwt")).build()));
    }

    @Test
    void theFirstRefusalWins() {
        DispatchReceiveRequest granted = openBot().grantTypes(Set.of(OAuth2Constants.PASSWORD)).build();
        assertEquals("jwks_uri is refused", check(
                new FetchUrls("https://example.com/jwks.json", null, List.of(), null),
                "http://127.0.0.1/",
                null,
                granted).orElseThrow());
        assertEquals("rootUrl is refused", check(FetchUrls.none(), "http://127.0.0.1/", null, granted).orElseThrow());
    }

    @Test
    void aMissingRequestIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DispatchReceiveRules.before(FetchUrls.none(), null, null, DNS, null));
    }

    @Test
    void policyNames() {
        assertEquals("freedriver-dispatch-receive", DispatchReceivePolicy.PROVIDER_ID);
        assertEquals("dispatch-receiver", DispatchReceivePolicy.ROLE);
        assertEquals("dispatch_receive", DispatchReceivePolicy.SCOPE);
        assertEquals("oauth2.device.authorization.grant.enabled", DispatchReceivePolicy.DEVICE_AUTHORIZATION_GRANT);
        assertEquals("oidc.ciba.grant.enabled", DispatchReceivePolicy.CIBA_GRANT);
        assertEquals("standard.token.exchange.enabled", DispatchReceivePolicy.TOKEN_EXCHANGE_GRANT);
        assertEquals("pkce.code.challenge.method", DispatchReceivePolicy.PKCE_CHALLENGE_METHOD);
        assertEquals("client.offline.session.idle.timeout", DispatchReceivePolicy.OFFLINE_SESSION_IDLE_TIMEOUT);
        assertEquals(OAuth2Constants.DEVICE_CODE_GRANT_TYPE, DispatchReceivePolicy.REFUSED_GRANT_TYPES.get(3));
    }

    private static DispatchReceiveRequest.Builder openBot() {
        return DispatchReceiveRequest.builder()
                .oidcScope("dispatch_receive offline_access")
                .grantTypes(Set.of("authorization_code", "refresh_token"));
    }

    private static DispatchReceiveRequest plain() {
        return DispatchReceiveRequest.builder().build();
    }

    private static String refusal(DispatchReceiveRequest request) {
        return check(FetchUrls.none(), null, null, request).orElseThrow();
    }

    private static Optional<String> check(
            FetchUrls fetch, String rootUrl, String adminUrl, DispatchReceiveRequest request) {
        return DispatchReceiveRules.before(fetch, rootUrl, adminUrl, DNS, request);
    }
}
