package io.freedriver.rolefixture.api;

/** An anonymous implementation in a static factory. */
public interface HeldFactory {
    static HeldApi load() {
        return new HeldApi() {
        };
    }
}
