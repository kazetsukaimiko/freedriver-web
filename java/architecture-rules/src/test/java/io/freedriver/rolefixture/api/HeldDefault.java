package io.freedriver.rolefixture.api;

/** An anonymous implementation in a default method. */
public interface HeldDefault {
    default HeldApi fallback() {
        return new HeldApi() {
        };
    }
}
