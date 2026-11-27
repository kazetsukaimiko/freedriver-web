package io.freedriver.architecture.fixtures.implpackage.impl.mongo;

import io.freedriver.architecture.fixtures.implpackage.service.Orders;

/** An implementation that reaches service code only from an anonymous class. */
public class AnonUsesService {
    void run() {
        new Runnable() {
            Orders orders;

            public void run() {
            }
        }.run();
    }
}
