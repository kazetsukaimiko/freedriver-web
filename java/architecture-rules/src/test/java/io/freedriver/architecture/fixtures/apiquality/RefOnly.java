package io.freedriver.architecture.fixtures.apiquality;

/** The only link to an API interface is a bound method reference. The line names this class. */
public class RefOnly {
    Callback held = this::lookup;

    String lookup(String id) {
        return id;
    }
}
