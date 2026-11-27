package io.freedriver.architecture.fixtures.apiquality;

/** A JAX-RS resource whose anonymous class implements an API that is not a JAX-RS contract. */
public class AnonOrdinaryResource {
    Ordinary other() {
        return new Ordinary() {
        };
    }
}
