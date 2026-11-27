package io.freedriver.architecture.fixtures.apiquality;

/** The lambda sits in an anonymous class. The line names this class. */
public class AnonHolds {
    Object go() {
        return new Object() {
            final Callback held = id -> id;
        };
    }
}
