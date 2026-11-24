package io.freedriver.rolefixture.impl.mongo;

import io.freedriver.rolefixture.api.Port;

/** Implements an API interface from an implementation package. */
public class MongoPort implements Port {
    @Override
    public String name() {
        return "mongo";
    }
}
