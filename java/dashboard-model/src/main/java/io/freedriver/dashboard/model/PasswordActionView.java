package io.freedriver.dashboard.model;

/**
 * Next hop to Keycloak {@code UPDATE_PASSWORD}. The value is a path on this host,
 * never a password, and never a Grok Bot redirect URI.
 */
public record PasswordActionView(String url) {
}
