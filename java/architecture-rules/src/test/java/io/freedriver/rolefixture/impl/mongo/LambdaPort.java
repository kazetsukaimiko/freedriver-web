package io.freedriver.rolefixture.impl.mongo;

import io.freedriver.rolefixture.api.Port;

/** A lambda of an API interface, held in an implementation package. */
public class LambdaPort {
    Port held = () -> "lambda";
}
