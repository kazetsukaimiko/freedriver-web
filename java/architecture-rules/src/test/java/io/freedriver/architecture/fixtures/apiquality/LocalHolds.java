package io.freedriver.architecture.fixtures.apiquality;

/** The lambda sits in a local class, so the line names that local class. */
public class LocalHolds {
    void go() {
        class Holder {
            final Callback held = id -> id;
        }
        new Holder();
    }
}
