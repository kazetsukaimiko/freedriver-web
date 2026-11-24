package io.freedriver.architecture.fixtures.implpackage.impl.mongo;

import io.freedriver.architecture.fixtures.implpackage.api.Port;

/** An implementation that uses its own API and a class of its own package. */
public class MongoPort implements Port {
    MongoHelper helper;
}
