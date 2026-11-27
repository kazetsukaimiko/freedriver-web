package io.freedriver.architecture.fixtures.apiquality;

/** Only allowlisted types inside an anonymous class. */
public class AnonAllowed {
    void run() {
        new Runnable() {
            public void run() {
                String.valueOf(1);
            }
        }.run();
    }
}
