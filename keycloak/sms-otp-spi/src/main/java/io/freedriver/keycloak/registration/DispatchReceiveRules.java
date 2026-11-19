package io.freedriver.keycloak.registration;

import org.keycloak.OAuth2Constants;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;

/**
 * Before-hooks refuse and after-hooks pin. A fetch URL is refused on every host.
 * rootUrl and adminUrl use an http or https scheme and no user-info; they are refused
 * when the URI does not parse, the host does not resolve, or any resolved address is
 * loopback, link-local, site-local, or any-local. Their path, query, and fragment stay.
 * A client that does not name dispatch_receive stops after those URL checks.
 *
 * <p>When several refusals apply, the first in this order is the one returned: the four
 * fetch fields, rootUrl, adminUrl, a missing realm scope, a refused grant type, the four
 * grant booleans, the three grant attributes, consent, PKCE, a protocol mapper,
 * access.token.lifespan, then a scope outside basic, dispatch_receive, and offline_access.
 */
public final class DispatchReceiveRules {

    private DispatchReceiveRules() {
    }

    public static boolean isDispatchReceive(DispatchReceiveRequest request) {
        return names(request.oidcScope())
                || request.defaultClientScopes().contains(DispatchReceivePolicy.SCOPE)
                || request.optionalClientScopes().contains(DispatchReceivePolicy.SCOPE)
                || request.storedDefaultScopes().contains(DispatchReceivePolicy.SCOPE);
    }

    /** OneYearTokens skips a stored client that holds the scope on either list. */
    public static boolean storedHoldsScope(Set<String> defaultScopes, Set<String> optionalScopes) {
        return holds(defaultScopes) || holds(optionalScopes);
    }

    public static Optional<DispatchReceivePin> pin(DispatchReceiveRequest request) {
        if (!isDispatchReceive(request)) {
            return Optional.empty();
        }
        return Optional.of(DispatchReceivePin.pinned());
    }

    public static Optional<String> before(
            FetchUrls fetch,
            String rootUrl,
            String adminUrl,
            HostResolver resolver,
            DispatchReceiveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request");
        }
        Optional<String> urls = fetchUrls(fetch == null ? FetchUrls.none() : fetch);
        if (urls.isPresent()) {
            return urls;
        }
        Optional<String> root = hostUrl("rootUrl", rootUrl, resolver);
        if (root.isPresent()) {
            return root;
        }
        Optional<String> admin = hostUrl("adminUrl", adminUrl, resolver);
        if (admin.isPresent()) {
            return admin;
        }
        if (!isDispatchReceive(request)) {
            return Optional.empty();
        }
        if (!request.realmHasBasic() || !request.realmHasDispatchReceive()) {
            return Optional.of("realm lacks basic or dispatch_receive");
        }
        for (String grant : DispatchReceivePolicy.REFUSED_GRANT_TYPES) {
            if (request.grantTypes().contains(grant)) {
                return Optional.of("grant is refused");
            }
        }
        if (request.directAccessGrantsEnabled()) {
            return Optional.of("direct access grants are refused");
        }
        if (request.implicitFlowEnabled()) {
            return Optional.of("implicit flow is refused");
        }
        if (request.serviceAccountsEnabled()) {
            return Optional.of("service accounts are refused");
        }
        if (request.authorizationServicesEnabled()) {
            return Optional.of("authorization services are refused");
        }
        if (request.deviceAuthorizationGrantEnabled()) {
            return Optional.of("device authorization grant is refused");
        }
        if (request.cibaGrantEnabled()) {
            return Optional.of("ciba grant is refused");
        }
        if (request.tokenExchangeEnabled()) {
            return Optional.of("token exchange is refused");
        }
        if (Boolean.FALSE.equals(request.consentRequired())) {
            return Optional.of("consent is required");
        }
        String pkce = request.pkceCodeChallengeMethod();
        if (pkce != null && !OAuth2Constants.PKCE_METHOD_S256.equals(pkce)) {
            return Optional.of("pkce method must be S256");
        }
        if (request.protocolMapper()) {
            return Optional.of("protocol mapper is refused");
        }
        if (request.accessTokenLifespan()) {
            return Optional.of("access.token.lifespan is refused");
        }
        if (disallowedScope(request)) {
            return Optional.of("scope is refused");
        }
        return Optional.empty();
    }

    private static Optional<String> fetchUrls(FetchUrls fetch) {
        if (present(fetch.jwksUri())) {
            return Optional.of("jwks_uri is refused");
        }
        if (present(fetch.sectorIdentifierUri())) {
            return Optional.of("sector_identifier_uri is refused");
        }
        for (String uri : fetch.requestUris()) {
            if (present(uri)) {
                return Optional.of("request_uris is refused");
            }
        }
        if (present(fetch.backchannelLogoutUri())) {
            return Optional.of("backchannel_logout_uri is refused");
        }
        return Optional.empty();
    }

    static Optional<String> hostUrl(String field, String raw, HostResolver resolver) {
        if (!present(raw)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (Exception bad) {
            return Optional.of(field + " is refused");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return Optional.of(field + " is refused");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of(field + " is refused");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.of(field + " is refused");
        }
        if (resolver == null) {
            throw new IllegalArgumentException("resolver");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException unknown) {
            return Optional.of(field + " is refused");
        }
        if (addresses == null || addresses.length == 0) {
            return Optional.of(field + " is refused");
        }
        for (InetAddress address : addresses) {
            if (address == null || local(address)) {
                return Optional.of(field + " is refused");
            }
        }
        return Optional.empty();
    }

    private static boolean local(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress();
    }

    private static boolean disallowedScope(DispatchReceiveRequest request) {
        if (disallowed(request.oidcScope())) {
            return true;
        }
        for (String scope : request.defaultClientScopes()) {
            if (!DispatchReceivePolicy.ALLOWED_SCOPES.contains(scope)) {
                return true;
            }
        }
        for (String scope : request.optionalClientScopes()) {
            if (!DispatchReceivePolicy.ALLOWED_SCOPES.contains(scope)) {
                return true;
            }
        }
        return false;
    }

    private static boolean disallowed(String oidcScope) {
        for (String token : oidcScope.split("\\s+")) {
            if (!token.isEmpty() && !DispatchReceivePolicy.ALLOWED_SCOPES.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(String oidcScope) {
        for (String token : oidcScope.split("\\s+")) {
            if (DispatchReceivePolicy.SCOPE.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holds(Set<String> scopes) {
        return scopes != null && scopes.contains(DispatchReceivePolicy.SCOPE);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
