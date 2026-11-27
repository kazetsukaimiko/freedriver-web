package io.freedriver.architecture.fixtures.apiquality;

/** A default method that calls a non-sealed API interface it does not implement. */
public interface Caller {
    default void go() {
        OtherApi.ping();
    }
}
