package io.freedriver.rolefixture.impl.mongo;

import io.freedriver.rolefixture.api.Port;

/** An implementation package whose anonymous class implements an API interface. */
public class AnonPort {
    Port port() {
        return new Port() {
            @Override
            public String name() {
                return "anon";
            }
        };
    }
}
