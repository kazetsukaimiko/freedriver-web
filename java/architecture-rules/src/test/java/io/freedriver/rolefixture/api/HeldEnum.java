package io.freedriver.rolefixture.api;

/** An enum method whose anonymous class implements an API interface. */
public enum HeldEnum {
    ONE;

    HeldApi port() {
        return new HeldApi() {
        };
    }
}
