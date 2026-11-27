package io.freedriver.architecture.fixtures.apiquality;

/** A JAX-RS resource holding a lambda of its JAX-RS contract. */
public class OneResource {
    OneApi api = () -> "ok";
}
