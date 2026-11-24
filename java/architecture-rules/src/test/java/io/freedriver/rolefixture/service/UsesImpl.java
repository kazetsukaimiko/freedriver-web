package io.freedriver.rolefixture.service;

import io.freedriver.rolefixture.impl.mongo.MongoPort;

/** Service code that names an implementation of its own module. */
public class UsesImpl {
    public String call(MongoPort port) {
        return port.name();
    }
}
