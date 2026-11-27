package io.freedriver.architecture.fixtures.apiquality;

/** A JAX-RS resource whose anonymous class implements its JAX-RS contract. */
public class AnonWidgetsResource {
    WidgetsApi api() {
        return new WidgetsApi() {
        };
    }
}
