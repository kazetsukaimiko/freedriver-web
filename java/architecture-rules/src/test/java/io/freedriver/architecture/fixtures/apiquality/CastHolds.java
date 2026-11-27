package io.freedriver.architecture.fixtures.apiquality;

/** An intersection cast whose functional type is an API interface. */
public class CastHolds {
    Object go() {
        return (Runnable & RunApi) () -> { };
    }
}
