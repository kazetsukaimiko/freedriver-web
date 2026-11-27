package io.freedriver.rolefixture.api;

/** Two anonymous implementations of this interface. Rule 3 reports one line. */
public interface HeldPort {
    HeldPort FIRST = new HeldPort() {
    };

    HeldPort SECOND = new HeldPort() {
    };
}
