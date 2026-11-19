package io.freedriver.architecture.fixtures.layering.bystander;

/**
 * No hexagonal role and no edge suffix, and it depends on neither JAX-RS nor the security identity.
 * The edge fixtures are evaluated together with this class so a rule whose {@code that} clause
 * would otherwise match nothing still checks a class.
 */
public final class Plain {
}
