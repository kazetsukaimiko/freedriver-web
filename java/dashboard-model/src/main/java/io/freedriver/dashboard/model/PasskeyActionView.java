package io.freedriver.dashboard.model;

/**
 * Next hop to Keycloak {@code webauthn-register-passwordless}. The value is a
 * path on this host. The dashboard origin is not the WebAuthn RP ID.
 */
public record PasskeyActionView(String url) {
}
