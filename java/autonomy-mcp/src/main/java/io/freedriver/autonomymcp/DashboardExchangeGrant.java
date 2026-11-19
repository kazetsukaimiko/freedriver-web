package io.freedriver.autonomymcp;

import java.util.Map;

/**
 * Extra parameters for one call to the {@code dashboard-exchange} client.
 * {@code scope} is null when the form omits that field. A blank scope is refused: an empty
 * {@code scope} value is not a request for no scopes.
 * <p>
 * {@link #parameters()} is a new map for that one request. It is not the shared
 * {@code grant-options} map. {@code subject_token_type}, {@code requested_token_type}, and
 * {@code audience} stay on the client configuration.
 */
public record DashboardExchangeGrant(String subjectToken, String scope) {

    public DashboardExchangeGrant {
        if (subjectToken == null || subjectToken.isBlank()) {
            throw new IllegalArgumentException("subject token is required");
        }
        if (scope != null && scope.isBlank()) {
            throw new IllegalArgumentException("scope is omitted, never blank");
        }
    }

    public static DashboardExchangeGrant of(String subjectToken, Object principal) {
        String scopes = ApplianceGrants.fromPrincipal(principal);
        return new DashboardExchangeGrant(subjectToken, scopes.isEmpty() ? null : scopes);
    }

    /** {@code subject_token}, plus {@code scope} only when this grant names one. */
    public Map<String, String> parameters() {
        if (scope == null) {
            return Map.of("subject_token", subjectToken);
        }
        return Map.of("subject_token", subjectToken, "scope", scope);
    }
}
