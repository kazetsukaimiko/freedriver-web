package io.freedriver.architecture.fixtures.apiquality;

/** A JAX-RS resource holding a lambda of an API interface that is not a JAX-RS contract. */
public class PlainResource {
    Callback api = id -> id;
}
