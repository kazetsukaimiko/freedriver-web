package io.freedriver.rolefixture.api;

/** A record method whose anonymous class implements an API interface. */
public record HeldRecord(String name) {
    HeldApi port() {
        return new HeldApi() {
        };
    }
}
