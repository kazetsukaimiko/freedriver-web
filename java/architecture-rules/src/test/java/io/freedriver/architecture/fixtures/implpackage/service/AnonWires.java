package io.freedriver.architecture.fixtures.implpackage.service;

import io.freedriver.architecture.fixtures.implpackage.impl.mongo.MongoPort;

/** Service code that names an implementation class only inside an anonymous class. */
public class AnonWires {
    void run() {
        new Runnable() {
            MongoPort port;

            public void run() {
            }
        }.run();
    }
}
