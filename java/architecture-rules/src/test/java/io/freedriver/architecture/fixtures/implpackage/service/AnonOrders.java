package io.freedriver.architecture.fixtures.implpackage.service;

/** Service code whose anonymous class uses only an allowed type. */
public class AnonOrders {
    void run() {
        new Runnable() {
            public void run() {
                String.valueOf(1);
            }
        }.run();
    }
}
