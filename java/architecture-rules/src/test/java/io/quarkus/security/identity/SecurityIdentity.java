package io.quarkus.security.identity;

/**
 * A stand-in for {@code io.quarkus.security.identity.SecurityIdentity}. The layering fixtures
 * depend on this name, so the architecture-rules module needs no Quarkus dependency.
 */
public interface SecurityIdentity {
}
