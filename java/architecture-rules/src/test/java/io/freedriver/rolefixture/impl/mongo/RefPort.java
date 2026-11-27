package io.freedriver.rolefixture.impl.mongo;

import io.freedriver.rolefixture.api.Port;

/** A method reference of an API interface, held in an implementation package. */
public class RefPort {
    Port held = this::lookup;

    String lookup() {
        return "ref";
    }
}
