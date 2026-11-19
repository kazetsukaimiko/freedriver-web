package io.freedriver.keycloak.registration;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the registration policy reads off a client representation and the stored client.
 * {@code publicClient} and {@code token_endpoint_auth_method} are absent: they are not a
 * refusal and the pin leaves them as registered.
 */
public record DispatchReceiveRequest(
        String oidcScope,
        Set<String> defaultClientScopes,
        Set<String> optionalClientScopes,
        Set<String> storedDefaultScopes,
        Set<String> grantTypes,
        boolean directAccessGrantsEnabled,
        boolean implicitFlowEnabled,
        boolean serviceAccountsEnabled,
        boolean authorizationServicesEnabled,
        boolean deviceAuthorizationGrantEnabled,
        boolean cibaGrantEnabled,
        boolean tokenExchangeEnabled,
        Boolean consentRequired,
        String pkceCodeChallengeMethod,
        boolean protocolMapper,
        boolean accessTokenLifespan,
        boolean realmHasBasic,
        boolean realmHasDispatchReceive) {

    public DispatchReceiveRequest {
        oidcScope = oidcScope == null ? "" : oidcScope;
        defaultClientScopes = names(defaultClientScopes);
        optionalClientScopes = names(optionalClientScopes);
        storedDefaultScopes = names(storedDefaultScopes);
        grantTypes = names(grantTypes);
        pkceCodeChallengeMethod = blank(pkceCodeChallengeMethod) ? null : pkceCodeChallengeMethod;
    }

    /** OpenBot v0.73.0's registration body for this endpoint, with the realm scopes present. */
    public static DispatchReceiveRequest openBot() {
        return builder()
                .oidcScope("dispatch_receive offline_access")
                .grantTypes(Set.of("authorization_code", "refresh_token"))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Set<String> names(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (!blank(value)) {
                copy.add(value);
            }
        }
        return Set.copyOf(copy);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static final class Builder {
        private String oidcScope = "";
        private Set<String> defaultClientScopes = Set.of();
        private Set<String> optionalClientScopes = Set.of();
        private Set<String> storedDefaultScopes = Set.of();
        private Set<String> grantTypes = Set.of();
        private boolean directAccessGrantsEnabled;
        private boolean implicitFlowEnabled;
        private boolean serviceAccountsEnabled;
        private boolean authorizationServicesEnabled;
        private boolean deviceAuthorizationGrantEnabled;
        private boolean cibaGrantEnabled;
        private boolean tokenExchangeEnabled;
        private Boolean consentRequired;
        private String pkceCodeChallengeMethod;
        private boolean protocolMapper;
        private boolean accessTokenLifespan;
        private boolean realmHasBasic = true;
        private boolean realmHasDispatchReceive = true;

        public Builder oidcScope(String oidcScope) {
            this.oidcScope = oidcScope;
            return this;
        }

        public Builder defaultClientScopes(Set<String> defaultClientScopes) {
            this.defaultClientScopes = defaultClientScopes;
            return this;
        }

        public Builder optionalClientScopes(Set<String> optionalClientScopes) {
            this.optionalClientScopes = optionalClientScopes;
            return this;
        }

        public Builder storedDefaultScopes(Set<String> storedDefaultScopes) {
            this.storedDefaultScopes = storedDefaultScopes;
            return this;
        }

        public Builder grantTypes(Set<String> grantTypes) {
            this.grantTypes = grantTypes;
            return this;
        }

        public Builder directAccessGrantsEnabled(boolean enabled) {
            this.directAccessGrantsEnabled = enabled;
            return this;
        }

        public Builder implicitFlowEnabled(boolean enabled) {
            this.implicitFlowEnabled = enabled;
            return this;
        }

        public Builder serviceAccountsEnabled(boolean enabled) {
            this.serviceAccountsEnabled = enabled;
            return this;
        }

        public Builder authorizationServicesEnabled(boolean enabled) {
            this.authorizationServicesEnabled = enabled;
            return this;
        }

        public Builder deviceAuthorizationGrantEnabled(boolean enabled) {
            this.deviceAuthorizationGrantEnabled = enabled;
            return this;
        }

        public Builder cibaGrantEnabled(boolean enabled) {
            this.cibaGrantEnabled = enabled;
            return this;
        }

        public Builder tokenExchangeEnabled(boolean enabled) {
            this.tokenExchangeEnabled = enabled;
            return this;
        }

        public Builder consentRequired(Boolean consentRequired) {
            this.consentRequired = consentRequired;
            return this;
        }

        public Builder pkceCodeChallengeMethod(String method) {
            this.pkceCodeChallengeMethod = method;
            return this;
        }

        public Builder protocolMapper(boolean protocolMapper) {
            this.protocolMapper = protocolMapper;
            return this;
        }

        public Builder accessTokenLifespan(boolean accessTokenLifespan) {
            this.accessTokenLifespan = accessTokenLifespan;
            return this;
        }

        public Builder realmHasBasic(boolean realmHasBasic) {
            this.realmHasBasic = realmHasBasic;
            return this;
        }

        public Builder realmHasDispatchReceive(boolean realmHasDispatchReceive) {
            this.realmHasDispatchReceive = realmHasDispatchReceive;
            return this;
        }

        public DispatchReceiveRequest build() {
            return new DispatchReceiveRequest(
                    oidcScope,
                    defaultClientScopes,
                    optionalClientScopes,
                    storedDefaultScopes,
                    grantTypes,
                    directAccessGrantsEnabled,
                    implicitFlowEnabled,
                    serviceAccountsEnabled,
                    authorizationServicesEnabled,
                    deviceAuthorizationGrantEnabled,
                    cibaGrantEnabled,
                    tokenExchangeEnabled,
                    consentRequired,
                    pkceCodeChallengeMethod,
                    protocolMapper,
                    accessTokenLifespan,
                    realmHasBasic,
                    realmHasDispatchReceive);
        }
    }
}
